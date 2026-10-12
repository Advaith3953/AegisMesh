# AegisMesh walkthrough (Java)

A guided tour: verify it works, watch it route around a slow backend and survive a leader crash, then read the code in
an order that builds up. The [README](README.md) is the reference; this is the "show me" version.

## 1. What you are looking at

```
clients ──► gateway gw1 / gw2 / gw3        DATA PLANE: pick a backend, retry, circuit-break, health-check
                 │  ▲
                 ▼  │  Raft                CONTROL PLANE: backend registry + routing policy, replicated
             backends w1 w2 w3 ...
```

* **Data plane.** Every gateway routes from its *own* live measurements of each backend (latency, in-flight requests,
  error rate, reported CPU, recent failures, kernel retransmissions).
* **Control plane.** The list of backends and each service's policy live in a Raft log, so all gateways agree, a
  config write survives losing the leader, and a restarted gateway rebuilds its config from its durable log.

Pure JDK (Java 21): the HTTP server, HTTP client, threads and JSON are all hand-wired from the standard library. The
only things outside the JDK are the test tools (JUnit 5, JaCoCo).

## 2. Prerequisites

| need | for | notes |
|---|---|---|
| JDK **21+** | everything | `java -version` and `javac -version` must both say 21 or newer |
| `make`, `bash`, `curl` | build, demo | |
| JUnit 5 console jar | tests | Ubuntu: `sudo apt-get install junit5`; otherwise `make test JUNIT=/path/to/junit-platform-console-standalone.jar` |
| JaCoCo | coverage gate only | Ubuntu: `sudo apt-get install libjacoco-java` |

## 3. Verify (about 3 minutes)

```bash
make check      # build (warnings are errors), 184 unit/integration tests, coverage gate, 9 process-level e2e tests, demo
```

or step by step:

| command | what it proves |
|---|---|
| `make build` | compiles with `-Xlint:all -Werror` and packages `out/aegismesh.jar` |
| `make test` | 184 tests: JSON, CLI, breaker, scoring, policies, router, proxy, health checks, Raft (partitions, crashes, durability), commands, 3-node clusters over real HTTP, the benchmark itself |
| `make cov` | the same tests under JaCoCo; fails below 85 % line coverage (currently 91 %) |
| `make e2e` | starts the packaged app as separate OS processes: probes, admin API, routing, a backend dying, `kill -9` of the leader, restart with durable state, SIGTERM exit code, bad flags, corrupt state |
| `make demo` | the scripted walk-through in the next section |

## 4. See it work: the demo

```bash
make demo
```

It starts 3 backends and 3 gateways, then:

1. **registers backends through a follower (gw2)**: the write is forwarded to the leader and replicated to everyone;
2. sends 60 requests through gw3 and prints how many each backend served;
3. makes `w1` 200 ms slower and sends 120 more: `w1` should all but vanish from the counts;
4. **kills the leader** (`kill -9`), waits for a new election, and routes again from the replicated config;
5. adds a fourth backend through a surviving node (the control plane is writable again) and prints the registry.

Reading the numbers: with *serial* `curl` traffic you will see output like `31 w1 / 1 w2 / 28 w3`. The adaptive policy
concentrates on whichever backend currently looks cheapest. That is the documented "no exploration" limitation (see the
README), not a bug in the demo. The benchmark uses concurrent load, where the in-flight term spreads requests out.

## 5. Drive it by hand

```bash
make build
P="gw1=127.0.0.1:9101,gw2=127.0.0.1:9102,gw3=127.0.0.1:9103"
bin/aegis-gateway -id gw1 -listen 127.0.0.1:8081 -peers $P      # repeat for gw2 (8082) and gw3 (8083)
bin/aegis-backend -id w1 -listen 127.0.0.1:7001

curl -XPOST 127.0.0.1:9102/admin/backends -d '{"id":"w1","service":"api","addr":"127.0.0.1:7001"}'
curl -XPOST 127.0.0.1:9102/admin/policy   -d '{"service":"api","policy":"adaptive"}'
curl -i 127.0.0.1:8081/api/work          # /<service>/<path>: "api" picks the pool and is stripped from the path
curl 127.0.0.1:8081/metrics              # Prometheus text: cost, EWMA latency, in-flight, breaker state ...
curl -i 127.0.0.1:8081/healthz           # liveness: 200 while the process is up
curl -i 127.0.0.1:8081/readyz            # readiness: 200 only with a leader and >= 1 healthy backend, else 503 + reason
curl 127.0.0.1:9101/admin/status         # Raft role/term/commit index + the replicated registry
curl -XPOST 127.0.0.1:7001/_fault -d '{"extra_ms":150}'    # make a backend slow
curl -XPOST 127.0.0.1:7001/_fault -d '{"error_rate":1}'    # make it fail
```

Admin API behaviour:

| request | result |
|---|---|
| valid command | `200 committed` |
| invalid command (bad policy name, backend address without `host:port`, negative weight, service name containing `/ ? # %`, unknown op, malformed JSON) | **`400`** with the reason; nothing is written to the log |
| wrong HTTP method | **`405`** with `Allow: POST` |
| no leader yet / no quorum / leader just moved | `503` (retry) |

Percent-encoded paths reach the backend exactly as sent: `/api/files/a%3Fb%3Dc` arrives as `/files/a%3Fb%3Dc`.

## 6. Reading order for the code

Each step only needs the ones before it. Everything is under `src/main/java/aegismesh/`.

| # | file | what to look for |
|---|---|---|
| 1 | `breaker/Breaker.java` | CLOSED → OPEN → HALF-OPEN; **tickets** carry a generation number so a late result from an old state is ignored; `cancel` frees a probe slot without a verdict (client hung up) |
| 2 | `lb/Backend.java`, `lb/ScoreConfig.java` | the **cost function**: EWMA latency × (1+in-flight) × (1+errors) × (1+CPU above 50 %) × (1+recent failures) × (1+kernel penalty) ÷ weight |
| 3 | `lb/Adaptive.java`, `lb/RoundRobin.java` | exact argmin up to 8 backends, "power of two choices" above that |
| 4 | `lb/Router.java` | pools per service; `reconcile` keeps the learned signals of unchanged backends |
| 5 | `lb/Gateway.java` | the proxy: pick → breaker ticket → forward → report → one retry on another backend (GET/HEAD only) |
| 6 | `lb/HealthChecker.java` | active probes: eject after 2 failures, re-introduce after 2 passes; carries the backend's CPU |
| 7 | `raft/Node.java`, `raft/Types.java` | election, replication with fast backup, no-op on election, "commit only current-term entries", apply loop |
| 8 | `raft/FileStorage.java` | write temp, fsync, atomic rename, fsync the directory; a corrupt file is an error, never "empty" |
| 9 | `control/Command.java`, `Fsm.java` | validation *before* the log; the deterministic state machine |
| 10 | `control/Plane.java`, `ControlServer.java` | forwarding writes to the leader; the admin HTTP API; readiness |
| 11 | `telemetry/` | `/proc/net/tcp` parsing and the kernel penalty |
| 12 | `app/GatewayNode.java` | how one gateway process is assembled; reused by the benchmark and the tests |
| 13 | `bench/Bench.java` | open-loop load generator, fault-injection scenarios, report writers |

### Two lifecycles worth tracing

**A request.** `DataPlane.proxy` → `Gateway.handle` → `Router.pick` (filter healthy + breaker-admitting backends, then
the policy) → `Breaker.allow` → `Gateway.forward` (per-attempt timeout, signals updated) → `Breaker.report`, or
`Breaker.cancel` if the client left → on a 5xx or network error of an idempotent request, retry once on a *different*
backend.

**A config change.** `POST /admin/backends` on any node → `Plane.submit` validates → a follower forwards to the leader →
`Node.propose` appends and replicates → majority ack → commit → `Fsm.apply` on every node → the change callback calls
`Router.setPolicies` and `Router.reconcile` → the data plane is updated.

## 7. What each test group pins down

| tests | key behaviours |
|---|---|
| `BreakerTest` | trips after N failures; half-open probe limit; stale tickets ignored; cancel frees the slot; concurrent use |
| `lb/ScoringTest`, `lb/PolicyRouterTest` | cost monotonic in every signal; EWMA smoothing; failure decay; race-free weight updates; reconcile keeps learned state; filtering by health and breaker |
| `GatewayTest` | prefix stripping; encoded paths verbatim; a dead backend hidden by retry; breaker keeps traffic away; POST never retried; 5xx relayed; timeouts retried; a vanished client neither blames nor credits the backend |
| `HealthCheckerTest`, `DataPlaneTest`, `SimWorkerTest` | ejection/re-introduction, probe endpoints, metrics, events, HEAD/405 handling, no Nagle stalls, capacity queueing, fault injection |
| `RaftTest` | one stable leader; ordered replication; leader crash; minority leader cannot commit and is overwritten; whole-cluster restart; durable votes; storage failure stops the node |
| `CommandFsmTest`, `JsonTest`, `CliTest` | validation, deterministic replicas under random input, strict JSON, flag parsing |
| `ClusterTest` | 3 real nodes over HTTP: writes via any node, 400/405/404, readiness, failover, restart catch-up, no-quorum 503, corrupt-state refusal |
| `bench/BenchTest` | statistics and reports; **resilience measurably cuts errors** when a backend crashes; the cluster scenario survives a leader kill |
| `E2ETest` (`make e2e`) | the packaged app as real processes, including `kill -9`, SIGTERM and bad flags |

Run one class: `java -jar /usr/share/java/junit-platform-console-standalone.jar execute --class-path out/classes:out/test-classes --select-class aegismesh.RaftTest`.

## 8. The benchmark

```bash
make bench BENCH_ARGS="-scale 0.3 -reps 1"      # quick (about a minute)
make bench                                       # full: 3 reps, about 10 minutes
```

Flags: `-scenarios a,b`, `-arms x,y`, `-reps N`, `-scale F` (shrink durations), `-rps N`, `-out DIR`. It writes
`results.json`, `summary.csv` and `summary.md`; `results/` in the repo holds a committed run. Numbers are for comparing
arms against each other on one machine: backends are simulated and everything shares one JVM and one loopback.

## 9. Limitations (say these before an interviewer does)

* Backends are simulated; absolute numbers only mean something relative to each other.
* Raft: static membership, no snapshots or log compaction, no pre-vote or check-quorum.
* `/admin/*` and `/raft/*` are **unauthenticated**: bind control addresses to a private interface.
* The proxy buffers whole responses in memory (fine for API traffic, not for large downloads).
* Adaptive routing needs concurrency to spread load (see section 4).
* Kernel telemetry reads `/proc/net/tcp` (Linux only). The earlier Go version of this project also had an eBPF source;
  that was not ported.
* A stricter command validator than an older cluster's: upgrade all nodes together.

## 10. Troubleshooting

| symptom | fix |
|---|---|
| `javac: command not found` | you have a JRE, not a JDK; install `openjdk-21-jdk-headless` |
| `make test` cannot find JUnit | `sudo apt-get install junit5` or pass `JUNIT=/path/to/junit-platform-console-standalone.jar` |
| `error: -id "x" must be listed in -peers` | the node id must appear in `-peers` as `id=host:port` |
| `503 no available backend for api` | no backend registered for that service name (the first path segment), or all are ejected |
| `400 ... must look like host:port` | backend `addr` needs a port, e.g. `127.0.0.1:7001` |
| writes return `503` right after start | no leader yet; wait about half a second and retry |
| `error: ... Address already in use` | pick another port or stop the other process |
| gateway exits with `corrupt raft state file` | the file under `-data` is damaged; restore it or delete it only if you accept that this node forgets its vote |
