package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.util.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts the real packaged application as separate OS processes (3 gateways + backends) and checks every
 * operator-visible behaviour. Run with {@code make e2e}; needs {@code -Daegis.jar=<path to out/aegismesh.jar>}.
 */
@Tag("e2e")
class E2ETest {
    private static final String JAR = System.getProperty("aegis.jar", "out/aegismesh.jar");
    private final List<Proc> procs = new ArrayList<>();

    /** A child process whose output is captured. */
    private final class Proc {
        final Process p;
        final StringBuffer out = new StringBuffer();
        final Thread reader;

        Proc(String... args) throws IOException {
            List<String> cmd = new ArrayList<>(List.of("java", "-jar", JAR));
            cmd.addAll(List.of(args));
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            reader.setDaemon(true);
            reader.start();
            procs.add(this);
        }

        boolean alive() {
            return p.isAlive();
        }

        void kill() throws InterruptedException {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }

        /**
         * SIGTERM, then wait for a voluntary exit. Uses the ProcessHandle: {@code Process.destroy()} would also close
         * our end of the output pipe, so the child could not report that it is shutting down.
         */
        int terminate() throws InterruptedException {
            p.toHandle().destroy();
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "did not exit within 10 s of SIGTERM; output:\n" + out);
            reader.join(3000); // the process is gone; wait until everything it printed has been read
            return p.exitValue();
        }

        int exitCode() throws InterruptedException {
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "did not exit by itself; output:\n" + out);
            reader.join(3000);
            return p.exitValue();
        }
    }

    @AfterEach
    void tearDown() {
        procs.forEach(p -> p.p.destroyForcibly());
    }

    private final class Cluster {
        final Map<String, String> data = new LinkedHashMap<>();
        final Map<String, String> ctl = new LinkedHashMap<>();
        final Map<String, Proc> gw = new LinkedHashMap<>();
        final Path dir;
        final String peers;

        Cluster(Path dir) throws IOException {
            this.dir = dir;
            List<String> parts = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                data.put("gw" + i, "127.0.0.1:" + TestUtil.freePort());
                ctl.put("gw" + i, "127.0.0.1:" + TestUtil.freePort());
                parts.add("gw" + i + "=" + ctl.get("gw" + i));
            }
            peers = String.join(",", parts);
            for (String id : data.keySet()) {
                startGw(id);
            }
            for (String id : data.keySet()) {
                TestUtil.eventually(id + " liveness", Duration.ofSeconds(30),
                        () -> TestUtil.get("http://" + data.get(id) + "/healthz").status() == 200);
            }
        }

        void startGw(String id) throws IOException {
            gw.put(id, new Proc("gateway", "-id", id, "-listen", data.get(id), "-peers", peers, "-data", dir.toString(),
                    "-health-interval", "100ms", "-telemetry", "none"));
        }

        String dataUrl(String id) {
            return "http://" + data.get(id);
        }

        String ctlUrl(String id) {
            return "http://" + ctl.get(id);
        }

        List<String> alive() {
            return gw.entrySet().stream().filter(e -> e.getValue().alive()).map(Map.Entry::getKey).toList();
        }

        String leader() {
            String[] l = new String[1];
            TestUtil.eventually("a leader", Duration.ofSeconds(30), () -> {
                for (String id : alive()) {
                    TestUtil.Resp r = TestUtil.get(ctlUrl(id) + "/admin/status");
                    if (r.status() == 200 && id.equals(Json.obj(Json.obj(Json.parse(r.body())).get("raft")).get("leader"))) {
                        l[0] = id;
                        return true;
                    }
                }
                return false;
            });
            return l[0];
        }

        Proc backend(String id) throws IOException {
            String addr = "127.0.0.1:" + TestUtil.freePort();
            Proc b = new Proc("backend", "-id", id, "-listen", addr, "-service-ms", "2");
            backendAddr.put(id, addr);
            TestUtil.eventually(id + " up", Duration.ofSeconds(30), () -> TestUtil.get("http://" + addr + "/healthz").status() == 200);
            return b;
        }

        final Map<String, String> backendAddr = new LinkedHashMap<>();

        void register(String via, String backendId) {
            TestUtil.eventually("register " + backendId, Duration.ofSeconds(30), () -> TestUtil.post(ctlUrl(via) + "/admin/backends",
                    "{\"id\":\"" + backendId + "\",\"service\":\"api\",\"addr\":\"" + backendAddr.get(backendId) + "\"}").status() == 200);
        }

        Map<String, Integer> traffic(String gwId, int n) {
            Map<String, Integer> counts = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                TestUtil.Resp r = TestUtil.get(dataUrl(gwId) + "/api/work");
                counts.merge(r.status() == 200 ? r.header("X-Worker") : "HTTP" + r.status(), 1, Integer::sum);
            }
            return counts;
        }
    }

    @Test
    void lifecycleProbesAdminApiRoutingAndMetrics(@TempDir Path dir) throws Exception {
        Cluster c = new Cluster(dir);
        String leader = c.leader();
        String follower = c.alive().stream().filter(x -> !x.equals(leader)).findFirst().orElseThrow();

        // a follower only knows the leader after its first heartbeat; before that it correctly says "no leader known"
        TestUtil.eventually("follower learns the leader", Duration.ofSeconds(30),
                () -> TestUtil.get(c.ctlUrl(follower) + "/admin/status").body().contains("\"leader\":\"" + leader + "\""));
        TestUtil.Resp notReady = TestUtil.get(c.dataUrl(follower) + "/readyz");
        assertEquals(503, notReady.status());
        assertTrue(notReady.body().contains("no backends registered"), notReady.body());
        assertEquals(200, TestUtil.get(c.dataUrl(follower) + "/healthz").status());
        assertEquals(503, TestUtil.get(c.dataUrl(follower) + "/api/work").status());

        assertEquals(400, TestUtil.post(c.ctlUrl(follower) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"bogus\"}").status());
        assertEquals(405, TestUtil.get(c.ctlUrl(follower) + "/admin/backends").status());

        for (String id : List.of("w1", "w2", "w3")) {
            c.backend(id);
            c.register(follower, id); // forwarded to the leader
        }
        assertEquals(200, TestUtil.post(c.ctlUrl(follower) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"roundrobin\"}").status());
        for (String id : c.alive()) {
            TestUtil.eventually(id + " converged", Duration.ofSeconds(30), () -> {
                String m = TestUtil.get(c.dataUrl(id) + "/metrics").body();
                return TestUtil.get(c.dataUrl(id) + "/readyz").status() == 200
                        && TestUtil.get(c.ctlUrl(id) + "/admin/status").body().contains("\"api\":\"roundrobin\"")
                        && m.split("aegis_backend_healthy\\{").length == 4;
            });
        }
        assertEquals(Map.of("w1", 10, "w2", 10, "w3", 10), c.traffic("gw3", 30));

        String metrics = TestUtil.get(c.dataUrl("gw3") + "/metrics").body();
        for (String id : List.of("w1", "w2", "w3")) {
            assertTrue(metrics.contains("aegis_backend_requests_total{service=\"api\",backend=\"" + id + "\""), id);
        }
        assertEquals(200, TestUtil.get(c.dataUrl(leader) + "/_aegis/events").status());
        assertEquals(404, TestUtil.get(c.dataUrl(leader) + "/").status());

        assertEquals(200, TestUtil.post(c.ctlUrl(follower) + "/admin/backends/remove?id=w3", "").status());
        TestUtil.eventually("w3 drained everywhere", Duration.ofSeconds(30), () -> c.alive().stream().allMatch(id ->
                !c.traffic(id, 6).containsKey("w3")));
        assertEquals(400, TestUtil.post(c.ctlUrl(follower) + "/admin/backends/remove", "").status());
    }

    @Test
    void aBackendDyingIsHiddenThenReportedWhenNoneAreLeftAndRecoveryIsAutomatic(@TempDir Path dir) throws Exception {
        Cluster c = new Cluster(dir);
        c.leader();
        String gw = "gw1";
        Proc w1 = c.backend("w1");
        c.backend("w2");
        c.register(gw, "w1");
        c.register(gw, "w2");
        TestUtil.eventually("both healthy", Duration.ofSeconds(30), () -> {
            String m = TestUtil.get(c.dataUrl(gw) + "/metrics").body();
            return m.split("state=\"closed\"\\} 1\n").length >= 3 && m.contains("backend=\"w2\"");
        });

        w1.kill();
        for (int i = 0; i < 40; i++) {
            assertEquals(200, TestUtil.get(c.dataUrl(gw) + "/api/work").status(), "request " + i + " must be hidden by the retry");
        }
        // ejected means healthy=0; the breaker label may read open or (after its timeout) half-open
        TestUtil.eventually("w1 ejected", Duration.ofSeconds(20), () -> java.util.regex.Pattern.compile(
                "aegis_backend_healthy\\{service=\"api\",backend=\"w1\",state=\"[a-z-]+\"\\} 0")
                .matcher(TestUtil.get(c.dataUrl(gw) + "/metrics").body()).find());

        for (Proc p : new ArrayList<>(procs)) {
            if (p.out.toString().contains("backend w2")) {
                p.kill();
            }
        }
        TestUtil.eventually("readiness drops", Duration.ofSeconds(20), () -> {
            TestUtil.Resp r = TestUtil.get(c.dataUrl(gw) + "/readyz");
            return r.status() == 503 && r.body().contains("no healthy backend");
        });
        int status = TestUtil.get(c.dataUrl(gw) + "/api/work").status();
        assertTrue(status == 502 || status == 503, "no backend left: " + status);
        assertEquals(200, TestUtil.get(c.dataUrl(gw) + "/healthz").status());

        Proc back = new Proc("backend", "-id", "w1", "-listen", c.backendAddr.get("w1"), "-service-ms", "2");
        assertTrue(back.alive());
        TestUtil.eventually("automatic recovery", Duration.ofSeconds(30), () -> TestUtil.get(c.dataUrl(gw) + "/api/work").status() == 200);
    }

    @Test
    void killingTheLeaderFailsOverAndTheRestartedNodeRejoinsWithItsDurableState(@TempDir Path dir) throws Exception {
        Cluster c = new Cluster(dir);
        String leader = c.leader();
        c.backend("w1");
        c.register(leader, "w1");
        Proc dead = c.gw.get(leader);
        dead.kill();

        List<String> survivors = c.alive();
        assertEquals(2, survivors.size());
        String newLeader = c.leader();
        assertNotEquals(leader, newLeader);
        for (String id : survivors) {
            TestUtil.eventually(id + " still routes", Duration.ofSeconds(30), () -> TestUtil.get(c.dataUrl(id) + "/api/work").status() == 200);
        }
        c.backend("w2");
        c.register(survivors.get(0), "w2");

        c.startGw(leader); // same data dir, same ports
        TestUtil.eventually("restarted node has the full replicated config", Duration.ofSeconds(40), () -> {
            TestUtil.Resp r = TestUtil.get(c.ctlUrl(leader) + "/admin/status");
            return r.status() == 200 && r.body().contains("\"w1\"") && r.body().contains("\"w2\"");
        });
        assertNotEquals(null, c.leader());
    }

    @Test
    void sigtermShutsDownGracefullyWithExitCodeZero(@TempDir Path dir) throws Exception {
        Cluster c = new Cluster(dir);
        c.leader();
        Proc g = c.gw.get("gw3");
        assertEquals(0, g.terminate(), g.out.toString());
        assertTrue(g.out.toString().contains("shutting down"), g.out.toString());
        assertEquals(-1, TestUtil.get(c.dataUrl("gw3") + "/healthz").status(), "the port must be released");
        c.leader(); // two of three nodes keep a quorum

        String addr = "127.0.0.1:" + TestUtil.freePort();
        Proc b = new Proc("backend", "-id", "wx", "-listen", addr);
        TestUtil.eventually("backend up", Duration.ofSeconds(30), () -> TestUtil.get("http://" + addr + "/healthz").status() == 200);
        assertEquals(0, b.terminate(), b.out.toString());
        assertTrue(b.out.toString().contains("shutting down"));
    }

    @Test
    void backendFaultInjectionEndpoint() throws Exception {
        String addr = "127.0.0.1:" + TestUtil.freePort();
        new Proc("backend", "-id", "f1", "-listen", addr, "-service-ms", "1");
        String u = "http://" + addr;
        TestUtil.eventually("up", Duration.ofSeconds(30), () -> TestUtil.get(u + "/healthz").status() == 200);
        assertEquals(200, TestUtil.get(u + "/work").status());
        assertEquals(200, TestUtil.post(u + "/_fault", "{\"error_rate\":1}").status());
        assertEquals(500, TestUtil.get(u + "/work").status());
        assertEquals(200, TestUtil.post(u + "/_fault", "{\"error_rate\":0,\"cpu\":0.9}").status());
        assertTrue(TestUtil.get(u + "/healthz").body().contains("\"cpu\":0.9"));
        TestUtil.Resp get = TestUtil.get(u + "/_fault");
        assertEquals(405, get.status());
        assertEquals("POST", get.header("Allow"));
        assertEquals(400, TestUtil.post(u + "/_fault", "not json").status());
    }

    private record Result(int code, String out) {}

    private Result run(String... args) throws Exception {
        Proc p = new Proc(args);
        int code = p.exitCode();
        return new Result(code, p.out.toString());
    }

    @Test
    void flagValidationGivesClearMessagesAndNonZeroExitCodes(@TempDir Path dir) throws Exception {
        String peer = "gw1=127.0.0.1:" + TestUtil.freePort();
        String d = dir.toString();
        String[][] cases = {
                {"must be listed in -peers", "gateway", "-peers", peer, "-data", d},
                {"must be listed in -peers", "gateway", "-id", "zz", "-peers", peer, "-data", d},
                {"bad -peers entry", "gateway", "-id", "gw1", "-peers", "gw1", "-data", d},
                {"unknown -telemetry", "gateway", "-id", "gw1", "-peers", peer, "-data", d, "-telemetry", "magic"},
                {"unknown -policy", "gateway", "-id", "gw1", "-peers", peer, "-data", d, "-policy", "random"},
                {"flag provided but not defined", "gateway", "-nope"},
                {"flag needs a value", "gateway", "-id"},
                {"invalid value", "gateway", "-health-interval", "soon"},
                {"flag provided but not defined", "backend", "-nope"},
                {"invalid value", "backend", "-capacity", "many"},
                {"unknown command", "frobnicate"}};
        for (String[] tc : cases) {
            String[] args = java.util.Arrays.copyOfRange(tc, 1, tc.length);
            Result r = run(args);
            assertNotEquals(0, r.code(), String.join(" ", args));
            assertTrue(r.out().contains(tc[0]), "expected '" + tc[0] + "' for " + String.join(" ", args) + " but got:\n" + r.out());
        }
        for (String cmd : new String[] {"gateway", "backend", "bench"}) {
            Result h = run(cmd, "-h");
            assertEquals(0, h.code(), cmd);
            assertTrue(h.out().contains("usage:"), cmd);
        }
        assertEquals(2, run().code());
        assertEquals(0, run("help").code());
    }

    @Test
    void aBackendOnAnOccupiedPortFailsWithAMessage() throws Exception {
        String addr = "127.0.0.1:" + TestUtil.freePort();
        new Proc("backend", "-id", "first", "-listen", addr);
        TestUtil.eventually("first up", Duration.ofSeconds(30), () -> TestUtil.get("http://" + addr + "/healthz").status() == 200);
        Result r = run("backend", "-id", "second", "-listen", addr);
        assertEquals(1, r.code());
        assertTrue(r.out().startsWith("error:"), r.out());
    }

    @Test
    void aCorruptRaftStateFileMakesTheGatewayRefuseToStart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("gw1.raft.json"), "{broken");
        Result r = run("gateway", "-id", "gw1", "-listen", "127.0.0.1:" + TestUtil.freePort(), "-peers",
                "gw1=127.0.0.1:" + TestUtil.freePort(), "-data", dir.toString(), "-telemetry", "none");
        assertEquals(1, r.code());
        assertTrue(r.out().contains("corrupt"), r.out());
        assertFalse(Collections.emptyList().iterator().hasNext());
    }

    @Test
    void theBenchmarkCommandRunsFromTheCommandLine(@TempDir Path dir) throws Exception {
        Result r = run("bench", "-scenarios", "normal", "-arms", "aegis-adaptive", "-reps", "1", "-scale", "0.2", "-rps", "80",
                "-out", dir.toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(Files.exists(dir.resolve("summary.md")));
        assertEquals(1, Json.arr(Json.parse(Files.readString(dir.resolve("results.json")))).size());
        Result bad = run("bench", "-scenarios", "nonsense");
        assertEquals(2, bad.code());
        assertTrue(bad.out().contains("unknown scenario"));
        assertEquals(2, run("bench", "-arms", "nonsense").code());
        assertEquals(2, run("bench", "-reps", "0").code());
    }
}
