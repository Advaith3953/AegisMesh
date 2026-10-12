package aegismesh.bench;

import aegismesh.app.GatewayNode;
import aegismesh.breaker.Breaker;
import aegismesh.control.Command;
import aegismesh.lb.DataPlane;
import aegismesh.lb.EventLog;
import aegismesh.lb.Gateway;
import aegismesh.lb.HealthChecker;
import aegismesh.lb.Readiness;
import aegismesh.lb.Router;
import aegismesh.lb.ScoreConfig;
import aegismesh.lb.Spec;
import aegismesh.raft.Role;
import aegismesh.sim.Worker;
import aegismesh.util.Cli;
import aegismesh.util.Json;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Compares routing strategies ("arms") under injected faults ("scenarios"). Everything runs in one JVM on the loopback
 * interface with simulated backends, so absolute numbers only mean something relative to each other.
 *
 * <p>Arms: {@code rr-naive} (round-robin, no health checks/breakers/retries), {@code rr-resilient} (round-robin
 * with health checks, breakers and retry) and {@code aegis-adaptive} (the cost-based router with the same resilience).
 * Load is open-loop: requests are issued on a fixed schedule whether or not earlier ones have finished, so a slow
 * backend cannot hide its damage by slowing the generator down.
 */
public final class Bench {
    private Bench() {}

    record Arm(String name, String policy, boolean resilient) {}

    private static final List<Arm> ARMS = List.of(new Arm("rr-naive", "roundrobin", false),
            new Arm("rr-resilient", "roundrobin", true), new Arm("aegis-adaptive", "adaptive", true));

    /** A fault injected {@code atFrac} of the way through a scenario. */
    record Event(double atFrac, Consumer<World> action) {}

    record Scenario(String name, String description, double seconds, double rpsMul, boolean cluster,
                    int[] capacities, List<Event> events) {}

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("normal", "healthy, identical backends", 8, 1, false, new int[] {8, 8, 8, 8}, List.of()),
                new Scenario("uneven_capacity", "backends have capacity 2/4/8/16", 8, 1.5, false,
                        new int[] {2, 4, 8, 16}, List.of()),
                new Scenario("high_latency", "w1 gains +150 ms at 30%", 9, 1, false, new int[] {8, 8, 8, 8},
                        List.of(new Event(0.3, w -> w.worker(0).update(c -> c.withExtraMs(150))))),
                new Scenario("backend_failure", "w1 crashes at 30%, restarts at 70%", 10, 1, false,
                        new int[] {8, 8, 8, 8},
                        List.of(new Event(0.3, w -> w.worker(0).kill()), new Event(0.7, w -> w.restart(0)))),
                new Scenario("cpu_saturation", "w1 reports 95% CPU and is 4x slower at 30%", 9, 1, false,
                        new int[] {8, 8, 8, 8},
                        List.of(new Event(0.3, w -> w.worker(0).update(c -> c.withCpu(0.95).withServiceMul(4))))),
                new Scenario("packet_loss", "w1 stalls 200 ms on half its requests at 30% (emulated loss)", 9, 1,
                        false, new int[] {8, 8, 8, 8},
                        List.of(new Event(0.3, w -> w.worker(0).update(c -> c.withLossRate(0.5))))),
                new Scenario("leader_failure", "3 gateways; the Raft leader dies at 40%", 10, 1, true,
                        new int[] {8, 8, 8, 8}, List.of(new Event(0.4, World::killLeader))));
    }

    // ------------------------------------------------------------------ the system under test

    /** Workers plus either one standalone gateway or a 3-node gateway cluster. */
    static final class World implements AutoCloseable {
        final List<Worker> workers = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        private final List<GatewayNode> nodes = new ArrayList<>();
        private DataPlane standalone;
        private HealthChecker health;
        private Path dataDir;

        Worker worker(int i) {
            return workers.get(i);
        }

        void restart(int i) {
            try {
                workers.get(i).restart();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void killLeader() {
            for (GatewayNode n : nodes) {
                if (n.node().status().role() == Role.LEADER) {
                    n.stop();
                    return;
                }
            }
        }

        static World start(Scenario sc, Arm arm) throws Exception {
            World w = new World();
            List<Spec> specs = new ArrayList<>();
            for (int i = 0; i < sc.capacities().length; i++) {
                Worker wk = new Worker("w" + (i + 1), new Worker.Config(sc.capacities()[i], 10, 1, 0, 0, 0, -1));
                wk.start("127.0.0.1:0");
                w.workers.add(wk);
                specs.add(new Spec("w" + (i + 1), "api", wk.addr(), 0));
            }
            if (!sc.cluster()) {
                Router router = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), arm.policy());
                router.reconcile(specs);
                Gateway gw = new Gateway(router, arm.resilient());
                w.standalone = new DataPlane("127.0.0.1:0", gw, () -> new Readiness(true, "", "standalone", 0, 0));
                w.standalone.start();
                w.urls.add("http://127.0.0.1:" + w.standalone.port());
                if (arm.resilient()) {
                    w.health = new HealthChecker(router, Duration.ofMillis(250));
                    w.health.start();
                }
                return w;
            }
            w.dataDir = Files.createTempDirectory("aegis-bench");
            Map<String, String> peers = new LinkedHashMap<>();
            List<Integer> dataPorts = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                peers.put("gw" + i, "127.0.0.1:" + freePort());
                dataPorts.add(freePort());
            }
            int i = 0;
            for (String id : peers.keySet()) {
                GatewayNode.Config c = new GatewayNode.Config();
                c.id = id;
                c.dataAddr = "127.0.0.1:" + dataPorts.get(i++);
                c.peers = peers;
                c.dataDir = w.dataDir;
                c.policy = arm.policy();
                c.resilient = arm.resilient();
                c.telemetry = "none";
                c.healthInterval = Duration.ofMillis(250);
                GatewayNode n = new GatewayNode(c);
                n.start();
                w.nodes.add(n);
                w.urls.add("http://" + c.dataAddr);
            }
            for (Spec s : specs) {
                w.nodes.get(0).plane().submitRetry(Command.addBackend(s), Duration.ofSeconds(10));
            }
            w.nodes.get(0).plane().submitRetry(Command.setPolicy("api", arm.policy()), Duration.ofSeconds(10));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            for (GatewayNode n : w.nodes) {
                while (!n.plane().readiness(n.router()).ready() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
            }
            return w;
        }

        @Override
        public void close() {
            if (health != null) {
                health.stop();
            }
            if (standalone != null) {
                standalone.stop();
            }
            for (GatewayNode n : nodes) {
                n.stop();
            }
            for (Worker wk : workers) {
                wk.kill();
            }
            if (dataDir != null) {
                try (var walk = Files.walk(dataDir)) {
                    walk.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ------------------------------------------------------------------ load generation and statistics

    /** One completed (or failed) client request. */
    record Sample(double startMs, double latencyMs, boolean ok, String worker) {}

    /** Summary statistics of a set of samples. */
    record Stats(long total, long errors, double rps, double p50, double p95, double p99,
                 Map<String, Double> share) {
        double errPct() {
            return total == 0 ? 0 : 100.0 * errors / total;
        }
    }

    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    static Stats stats(List<Sample> samples, double windowSeconds) {
        List<Double> lat = new ArrayList<>();
        Map<String, Long> per = new TreeMap<>();
        long errors = 0;
        for (Sample s : samples) {
            if (s.ok()) {
                lat.add(s.latencyMs());
                per.merge(s.worker(), 1L, Long::sum);
            } else {
                errors++;
            }
        }
        Collections.sort(lat);
        Map<String, Double> share = new TreeMap<>();
        for (Map.Entry<String, Long> e : per.entrySet()) {
            share.put(e.getKey(), 100.0 * e.getValue() / Math.max(1, lat.size()));
        }
        return new Stats(samples.size(), errors, windowSeconds <= 0 ? 0 : lat.size() / windowSeconds,
                percentile(lat, 50), percentile(lat, 95), percentile(lat, 99), share);
    }

    /** Issues {@code rps} requests per second for {@code seconds}, firing {@code events} on schedule. */
    static List<Sample> drive(World w, double rps, double seconds, List<Event> events) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        ConcurrentLinkedQueue<Sample> out = new ConcurrentLinkedQueue<>();
        long t0 = System.nanoTime();
        long gap = (long) (1e9 / rps);
        long total = (long) (rps * seconds);
        List<Thread> threads = new ArrayList<>();
        boolean[] fired = new boolean[events.size()];
        for (long i = 0; i < total; i++) {
            long due = t0 + i * gap;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            double nowSec = (System.nanoTime() - t0) / 1e9;
            for (int e = 0; e < events.size(); e++) {
                if (!fired[e] && nowSec >= events.get(e).atFrac() * seconds) {
                    fired[e] = true;
                    Consumer<World> act = events.get(e).action();
                    Thread.startVirtualThread(() -> act.accept(w)); // faults must not stall the schedule
                }
            }
            final long idx = i;
            final long issuedAt = System.nanoTime();
            threads.add(Thread.startVirtualThread(() -> out.add(request(client, w, idx, t0, issuedAt))));
        }
        for (Thread t : threads) {
            t.join(5000);
        }
        client.close();
        return new ArrayList<>(out);
    }

    private static Sample request(HttpClient client, World w, long i, long t0, long issuedAt) {
        int n = w.urls.size();
        for (int k = 0; k < n; k++) { // a client that sees a dead gateway tries the next one
            String base = w.urls.get((int) ((i + k) % n));
            try {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base + "/api/work"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return new Sample((issuedAt - t0) / 1e6, (System.nanoTime() - issuedAt) / 1e6, true,
                            r.headers().firstValue("X-Worker").orElse("?"));
                }
                if (r.statusCode() < 500) {
                    break;
                }
            } catch (IOException e) {
                // try the next gateway
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Sample((issuedAt - t0) / 1e6, (System.nanoTime() - issuedAt) / 1e6, false, "");
    }

    // ------------------------------------------------------------------ running and reporting

    record Run(String scenario, String arm, int rep, Stats all, Stats after, String note) {}

    static Run runOne(Scenario sc, Arm arm, int rep, double rps, double scale) throws Exception {
        double seconds = Math.max(1.5, sc.seconds() * scale);
        try (World w = World.start(sc, arm)) {
            List<Sample> samples = drive(w, rps * sc.rpsMul(), seconds, sc.events());
            double faultAtMs = sc.events().isEmpty() ? 0 : sc.events().get(0).atFrac() * seconds * 1000;
            List<Sample> after = new ArrayList<>();
            for (Sample s : samples) {
                if (s.startMs() >= faultAtMs) {
                    after.add(s);
                }
            }
            return new Run(sc.name(), arm.name(), rep, stats(samples, seconds),
                    sc.events().isEmpty() ? stats(samples, seconds) : stats(after, seconds - faultAtMs / 1000), "");
        }
    }

    static Map<String, Object> toMap(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", r.scenario());
        m.put("arm", r.arm());
        m.put("rep", r.rep());
        m.put("overall", statsMap(r.all()));
        m.put("after_fault", statsMap(r.after()));
        return m;
    }

    private static Map<String, Object> statsMap(Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", s.total());
        m.put("errors", s.errors());
        m.put("throughput_rps", round(s.rps()));
        m.put("p50_ms", round(s.p50()));
        m.put("p95_ms", round(s.p95()));
        m.put("p99_ms", round(s.p99()));
        Map<String, Object> sh = new LinkedHashMap<>();
        s.share().forEach((k, v) -> sh.put(k, round(v)));
        m.put("traffic_share_pct", sh);
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double mean(List<Run> rs, java.util.function.ToDoubleFunction<Run> f) {
        return rs.stream().mapToDouble(f).average().orElse(0);
    }

    static String csv(List<Run> runs) {
        StringBuilder sb = new StringBuilder(
                "scenario,arm,reps,throughput_rps,p50_ms,p95_ms,p99_ms,error_pct,after_fault_p99_ms,after_fault_error_pct,w1_share_after_pct\n");
        Map<String, List<Run>> groups = new LinkedHashMap<>();
        for (Run r : runs) {
            groups.computeIfAbsent(r.scenario() + "|" + r.arm(), k -> new ArrayList<>()).add(r);
        }
        for (List<Run> g : groups.values()) {
            sb.append(String.format(Locale.ROOT, "%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%.2f,%.1f,%.2f,%.1f%n",
                    g.get(0).scenario(), g.get(0).arm(), g.size(), mean(g, r -> r.all().rps()),
                    mean(g, r -> r.all().p50()), mean(g, r -> r.all().p95()), mean(g, r -> r.all().p99()),
                    mean(g, r -> r.all().errPct()), mean(g, r -> r.after().p99()), mean(g, r -> r.after().errPct()),
                    mean(g, r -> r.after().share().getOrDefault("w1", 0.0))));
        }
        return sb.toString();
    }

    static String markdown(List<Run> runs, double scale, double rps) {
        StringBuilder sb = new StringBuilder("# AegisMesh benchmark\n\n");
        sb.append(String.format(Locale.ROOT, "Open-loop load of %.0f req/s (x scenario factor), duration scale %.2f, "
                + "simulated backends on loopback in one JVM. Compare arms **within** a scenario; absolute numbers "
                + "depend on the machine. \"After fault\" covers the time from the first injected fault to the end.%n%n",
                rps, scale));
        Map<String, List<Run>> byScenario = new LinkedHashMap<>();
        for (Run r : runs) {
            byScenario.computeIfAbsent(r.scenario(), k -> new ArrayList<>()).add(r);
        }
        for (Scenario sc : scenarios()) {
            List<Run> rs = byScenario.get(sc.name());
            if (rs == null) {
                continue;
            }
            sb.append("## ").append(sc.name()).append(" — ").append(sc.description()).append("\n\n");
            sb.append("| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |\n");
            sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            Map<String, List<Run>> byArm = new LinkedHashMap<>();
            for (Run r : rs) {
                byArm.computeIfAbsent(r.arm(), k -> new ArrayList<>()).add(r);
            }
            for (List<Run> g : byArm.values()) {
                sb.append(String.format(Locale.ROOT, "| %s | %.0f | %.1f | %.1f | %.1f | %.2f | %.1f | %.2f | %.1f |%n",
                        g.get(0).arm(), mean(g, r -> r.all().rps()), mean(g, r -> r.all().p50()),
                        mean(g, r -> r.all().p95()), mean(g, r -> r.all().p99()), mean(g, r -> r.all().errPct()),
                        mean(g, r -> r.after().p99()), mean(g, r -> r.after().errPct()),
                        mean(g, r -> r.after().share().getOrDefault("w1", 0.0))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Cli cli = new Cli("aegismesh bench")
                .string("out", "results", "output directory")
                .integer("reps", 3, "repetitions per scenario and arm")
                .number("scale", 1.0, "multiply scenario durations (use 0.3 for a quick run)")
                .number("rps", 400, "base request rate")
                .string("scenarios", "", "comma-separated subset (default all)")
                .string("arms", "", "comma-separated subset of rr-naive,rr-resilient,aegis-adaptive (default all)");
        try {
            cli.parse(args);
        } catch (Cli.CliException e) {
            if (e.helpRequested()) {
                System.out.print(e.getMessage());
                return;
            }
            System.err.println("error: " + e.getMessage());
            System.err.print(cli.usage());
            System.exit(2);
        }
        List<String> wantS = split(cli.getString("scenarios"));
        List<String> wantA = split(cli.getString("arms"));
        for (String s : wantS) {
            if (scenarios().stream().noneMatch(x -> x.name().equals(s))) {
                System.err.println("error: unknown scenario '" + s + "' (have "
                        + scenarios().stream().map(Scenario::name).toList() + ")");
                System.exit(2);
            }
        }
        for (String a : wantA) {
            if (ARMS.stream().noneMatch(x -> x.name().equals(a))) {
                System.err.println("error: unknown arm '" + a + "' (have " + ARMS.stream().map(Arm::name).toList() + ")");
                System.exit(2);
            }
        }
        int reps = cli.getInt("reps");
        double scale = cli.getNumber("scale");
        double rps = cli.getNumber("rps");
        if (reps < 1 || scale <= 0 || rps <= 0) {
            System.err.println("error: -reps, -scale and -rps must be positive");
            System.exit(2);
        }
        Path out = Path.of(cli.getString("out"));
        Files.createDirectories(out);

        // JIT-compile and warm connection paths of EVERY arm first, or whichever arm runs first looks artificially slow
        System.out.println("warming up the JVM ...");
        for (Arm arm : ARMS) {
            runOne(scenarios().get(0), arm, 0, rps, 0.2);
        }

        List<Run> runs = new ArrayList<>();
        long start = System.nanoTime();
        for (Scenario sc : scenarios()) {
            if (!wantS.isEmpty() && !wantS.contains(sc.name())) {
                continue;
            }
            for (Arm arm : ARMS) {
                if (!wantA.isEmpty() && !wantA.contains(arm.name())) {
                    continue;
                }
                for (int rep = 0; rep < reps; rep++) {
                    Run r = runOne(sc, arm, rep, rps, scale);
                    runs.add(r);
                    System.out.printf(Locale.ROOT, "[%3ds] %-16s %-15s rep %d  ok=%d err=%d  p50=%.1f p99=%.1f ms  %.0f rps%n",
                            (System.nanoTime() - start) / 1_000_000_000L, sc.name(), arm.name(), rep,
                            r.all().total() - r.all().errors(), r.all().errors(), r.all().p50(), r.all().p99(),
                            r.all().rps());
                }
            }
        }
        List<Object> json = new ArrayList<>();
        runs.forEach(r -> json.add(toMap(r)));
        Files.writeString(out.resolve("results.json"), Json.write(json) + "\n");
        Files.writeString(out.resolve("summary.csv"), csv(runs));
        Files.writeString(out.resolve("summary.md"), markdown(runs, scale, rps));
        System.out.println("done; wrote " + out.resolve("results.json") + ", summary.csv, summary.md");
    }

    private static List<String> split(String s) {
        return s.isEmpty() ? List.of() : Arrays.asList(s.split(","));
    }
}
