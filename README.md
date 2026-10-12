# AegisMesh: a fault-tolerant, adaptive API gateway in Java

> New here? Read [`WALKTHROUGH.md`](WALKTHROUGH.md) for a guided tour, expected outputs and a code reading order.

## Run it in your browser

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/YOUR_GITHUB_USERNAME/aegismesh?quickstart=1)

Click the badge (a free GitHub account is enough). Codespaces starts a cloud machine from [`.devcontainer/`](.devcontainer/devcontainer.json),
installs everything and builds the project; the first start takes a few minutes. Nothing to install locally. Once the terminal opens:

```bash
make demo     # 3 gateways + 3 backends: slow backend, leader kill, live reconfiguration (about 30 s)
make check    # build, tests, coverage gate, process e2e, demo (about 3 minutes)
```

AegisMesh is a gateway, not a web page: the demo starts real processes and prints what they do in the terminal.

AegisMesh is a reverse proxy that **routes around trouble**. Instead of spreading requests evenly, each gateway scores
every backend from live measurements (latency, queueing, errors, reported CPU, recent failures, kernel TCP
retransmissions) and sends traffic where it will be served fastest. Around that sit the things a production gateway
needs: active health checks, circuit breakers, safe retries, and a **replicated control plane built on a from-scratch
Raft implementation** so a fleet of gateways agrees on which backends exist and how to route to them, even when a
gateway dies.

* **Pure JDK 21.** The HTTP server, HTTP client, virtual threads and a strict JSON parser are all standard library or
  written here. No third-party runtime dependencies.
* **Verified.** 184 unit and in-process integration tests, 9 process-level end-to-end tests (real `java -jar`
  processes, `kill -9`, SIGTERM), 91 % line coverage, a warning-free build (`-Xlint:all -Werror`), and a benchmark
  harness that measures the claims below.

```
clients ──► gateway gw1 / gw2 / gw3         DATA PLANE (per gateway)
                 │   ▲                        pick backend by cost → breaker → forward → retry once elsewhere
                 ▼   │  Raft (HTTP/JSON)      health checks · metrics · /healthz /readyz
             ┌── replicated config ──┐      CONTROL PLANE
             │ backends, policies    │        durable log, leader election, writes forwarded to the leader
             └───────────────────────┘
                 backends w1 w2 w3 ...
```

## Quick start

Requires JDK 21+, `make`, `curl`. Tests also need JUnit 5 and JaCoCo (`sudo apt-get install junit5 libjacoco-java`).

```bash
make build                   # compile (warnings are errors) and package out/aegismesh.jar
make demo                    # 3 gateways + 3 backends: slow backend, leader kill, live reconfiguration
make check                   # everything CI runs: build, tests, coverage gate, process e2e, demo
```

Run it yourself:

```bash
P="gw1=127.0.0.1:9101,gw2=127.0.0.1:9102,gw3=127.0.0.1:9103"      # id=control-address of every gateway
bin/aegis-gateway -id gw1 -listen 127.0.0.1:8081 -peers $P         # repeat for gw2 (8082) and gw3 (8083)
bin/aegis-backend -id w1 -listen 127.0.0.1:7001                    # a simulated backend

curl -XPOST 127.0.0.1:9102/admin/backends -d '{"id":"w1","service":"api","addr":"127.0.0.1:7001"}'
curl -i 127.0.0.1:8081/api/work                                    # /<service>/<path> is routed to that service's pool
```

## How routing works

For each request the gateway filters out ejected backends and backends whose circuit breaker refuses, then the
**policy** picks one of the rest. The adaptive policy minimises

```
cost = ewmaLatencyMs × (1 + inflight) × (1 + 20·errorRate) × (1 + 2·cpuPressure)
                     × (1 + 0.5·recentFailures) × (1 + kernelPenalty)  ÷  weight
```

Multiplicative terms mean "this backend looks N times worse", and `inflight` makes the cost react immediately to
queueing, before any latency sample has come back. Up to 8 candidates are compared exactly; above that, "power of two
choices" samples two and takes the cheaper. Round-robin is available as the baseline (`-policy roundrobin`, or per
service through the admin API).

On a 5xx or network error of an idempotent request (GET/HEAD) the gateway retries once on a *different* backend. A
breaker trips after 5 consecutive failures, probes again after 1 s, and a request abandoned by its client is released
without counting for or against the backend.

## Interfaces

**Data port** (`-listen`): everything is proxied except these reserved paths, which therefore cannot be service names:

| path | purpose |
|---|---|
| `/healthz` | liveness: 200 while the process is up |
| `/readyz` | readiness: 200 only when a Raft leader is known and at least one backend is healthy, otherwise 503 with a JSON reason |
| `/metrics` | Prometheus text: health, EWMA latency, error rate, in-flight, CPU, kernel penalty, cost, request and failure counters per backend |
| `/_aegis/events` | JSON event log: ejections, re-introductions, breaker transitions |

**Control port** (the node's address in `-peers`): Raft RPCs under `/raft/*` and the admin API. Every write may be sent
to any node; a follower forwards it to the leader.

| request | meaning |
|---|---|
| `POST /admin/backends` `{"id","service","addr","weight"}` | register or replace a backend |
| `POST /admin/backends/remove?id=w1` | remove a backend |
| `POST /admin/policy` `{"service","policy"}` | `adaptive` or `roundrobin` for one service |
| `GET /admin/status` | Raft role, term, commit index and the replicated registry |

Status codes: `200 committed`; **`400`** invalid input with a reason (nothing is written to the log); **`405`** wrong
method (with `Allow: POST`); `503` no leader or no quorum. These endpoints are **unauthenticated**: bind them to a
private interface.

**Commands** (`java -jar out/aegismesh.jar <command> -h` lists every flag):

| command | main flags |
|---|---|
| `gateway` | `-id`, `-listen`, `-peers id=addr,...`, `-data DIR`, `-policy`, `-resilient`, `-telemetry none\|proc`, `-health-interval` |
| `backend` | `-id`, `-listen`, `-capacity`, `-service-ms`, `-extra-ms`, `-error-rate`; faults at runtime via `POST /_fault` |
| `bench` | `-scenarios`, `-arms`, `-reps`, `-scale`, `-rps`, `-out` |

Bad flags, an `-id` that is not in `-peers`, an unknown policy or a corrupt state file make the process exit with a
clear message and a non-zero code (2 for usage errors, 1 for startup failures). SIGTERM triggers a graceful shutdown
and exit code 0.

## Benchmarks

Open-loop load of 300 requests/s (450 for `uneven_capacity`) against 4 simulated backends, two repetitions per cell; the fault is injected 30-40 % of the way through each run. Each cell is **p99 latency in ms after the fault / client-visible errors in %**. Arms: `rr-naive` = round-robin with no health checks, breakers or retries; `rr-resilient` = round-robin plus those; `aegis-adaptive` = cost-based routing plus the same resilience.

| scenario | fault | rr-naive | rr-resilient | aegis-adaptive |
|---|---|---:|---:|---:|
| normal | healthy, identical backends | 16 ms / 0.0 % | 18 ms / 0.0 % | 14 ms / 0.0 % |
| uneven_capacity | backend capacities 2/4/8/16 | 12 ms / 0.0 % | 17 ms / 0.0 % | 13 ms / 0.0 % |
| high_latency | w1 gains +150 ms | 920 ms / 13.7 % | 1013 ms / 0.0 % | 12 ms / 0.0 % |
| backend_failure | w1 crashes, later restarts | 13 ms / 14.3 % | 23 ms / 0.0 % | 12 ms / 0.0 % |
| cpu_saturation | w1 reports 95 % CPU, 4x slower | 44 ms / 0.0 % | 44 ms / 0.0 % | 12 ms / 0.0 % |
| packet_loss | w1 stalls 200 ms on half its requests | 481 ms / 0.0 % | 521 ms / 0.0 % | 26 ms / 0.0 % |
| leader_failure | the Raft leader dies (3 gateways) | 14 ms / 0.0 % | 14 ms / 0.0 % | 15 ms / 0.0 % |

What the numbers show:

* **A slow or degraded backend** (`high_latency`, `cpu_saturation`, `packet_loss`): round-robin keeps feeding it 25 % of the traffic, so tail latency suffers: about 4x worse for a 4x-slower backend (44 vs 12 ms), and 20-80x worse when the backend stalls or is overloaded (a +150 ms backend receiving 75 req/s exceeds its capacity, its queue grows until requests hit the 1 s attempt timeout, so p99 is about 1 s). The adaptive router notices within a few requests and sends the degraded backend about 0-0.5 % of the traffic, keeping p99 at 12-26 ms.
* **A crashed backend** (`backend_failure`): without resilience about 14 % of requests after the crash fail; health checks, breakers and retries remove all client-visible errors, and the adaptive arm also avoids the dead backend immediately.
* **Health checks alone are not enough for slowness:** `rr-resilient` has no errors in `high_latency` (retries hide the timeouts) but still pays about a second of latency, because a slow backend is still "alive".
* **No cost when nothing is wrong** (`normal`, `uneven_capacity`): all arms are within noise of each other.
* **Control-plane failure does not disturb the data plane** (`leader_failure`): killing the Raft leader of a 3-gateway cluster causes no errors; clients simply move to a surviving gateway.

Caveats: backends are simulated and share one JVM and one loopback interface with the gateways and load generator, on a single CPU core here, so treat absolute numbers as indicative only and compare arms within a row. Reproduce with `make bench` (full, about 10 minutes) or `make bench BENCH_ARGS="-scale 0.3 -reps 1"` (quick). Full per-arm tables (p50/p95/p99, throughput, traffic share) are in [`results/summary.md`](results/summary.md).

## Quality gates

| gate | command | currently |
|---|---|---|
| compile with all lint warnings as errors | `make build` | clean |
| unit + in-process integration tests | `make test` | 184 passing |
| line coverage (JaCoCo) | `make cov` | 91 % (gate: 85 %) |
| real processes: failover, `kill -9`, SIGTERM, flags, corrupt state | `make e2e` | 9 passing |
| scripted demo | `make demo` | passes |

Highlights of what the tests prove: the Raft layer elects one stable leader, replicates in order, never lets a
minority leader commit and overwrites its uncommitted entries after a partition heals, recovers from a whole-cluster
restart, and stops itself rather than acknowledge state it could not persist; replicas stay identical under 3000
random (and corrupted) commands; invalid admin commands are refused before they reach the log; a percent-encoded path
reaches the backend byte-for-byte; a client that disconnects mid-request is never counted against a backend; small
keep-alive responses are not stalled by Nagle's algorithm (a bug the benchmark exposed, now covered by a regression
test).

## Project layout

```
src/main/java/aegismesh/
  breaker/   circuit breaker state machine
  lb/        backend scoring, policies, router, health checker, proxy gateway, data-plane HTTP server
  raft/      consensus node, JSON-over-HTTP and in-memory transports, durable storage
  control/   validated commands, replicated state machine, admin API, leader forwarding, readiness
  telemetry/ /proc/net/tcp source and the kernel-penalty feeder
  sim/       simulated backend with injectable faults
  app/       GatewayNode (assembles one gateway), command-line entry point
  bench/     load generator, fault scenarios, reports
  util/      strict JSON, flag parser, HTTP helpers
src/test/java/aegismesh/   the test suite (JUnit 5)
scripts/demo.sh            the scripted demo      tools/CoverageReport.java   JaCoCo gate
```

## Limitations

* Backends in the benchmark are simulated, and gateways, backends and the load generator share one JVM: compare arms
  against each other, not against other machines.
* Raft has static membership, no snapshots or log compaction, and no pre-vote or check-quorum.
* The control and admin endpoints have no authentication or TLS.
* The proxy buffers whole responses (fine for API traffic, not for very large downloads).
* The adaptive policy concentrates serial, low-concurrency traffic on the cheapest backend (it does not explore); under
  concurrent load the in-flight term spreads requests out.
* Kernel telemetry reads `/proc/net/tcp`, so it is Linux only (`-telemetry none` elsewhere).
