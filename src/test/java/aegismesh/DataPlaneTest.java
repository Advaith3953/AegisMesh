package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.breaker.Breaker;
import aegismesh.lb.DataPlane;
import aegismesh.lb.EventLog;
import aegismesh.lb.Gateway;
import aegismesh.lb.Readiness;
import aegismesh.lb.Router;
import aegismesh.lb.ScoreConfig;
import aegismesh.lb.Spec;
import aegismesh.sim.Worker;
import aegismesh.util.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataPlaneTest {
    private Worker w;
    private DataPlane dp;
    private Router router;
    private String base;
    private final AtomicReference<Readiness> ready = new AtomicReference<>(new Readiness(false, "no raft leader known", "", 0, 0));

    @BeforeEach
    void setUp() throws Exception {
        w = new Worker("w1", new Worker.Config().withServiceMs(1));
        w.start("127.0.0.1:0");
        router = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), "adaptive");
        router.reconcile(List.of(new Spec("w1", "api", w.addr(), 0)));
        dp = new DataPlane("127.0.0.1:0", new Gateway(router, true), ready::get);
        dp.start();
        base = "http://127.0.0.1:" + dp.port();
    }

    @AfterEach
    void tearDown() {
        dp.stop();
        w.kill();
    }

    @Test
    void livenessIsAlwaysOk() {
        TestUtil.Resp r = TestUtil.get(base + "/healthz");
        assertEquals(200, r.status());
        assertEquals("ok", Json.obj(Json.parse(r.body())).get("status"));
        assertEquals(200, TestUtil.http("HEAD", base + "/healthz", null).status());
        TestUtil.Resp post = TestUtil.post(base + "/healthz", "");
        assertEquals(405, post.status());
        assertTrue(post.header("Allow").contains("GET"));
    }

    @Test
    void readinessFollowsTheSupplierAndExplainsWhyNot() {
        TestUtil.Resp no = TestUtil.get(base + "/readyz");
        assertEquals(503, no.status());
        Map<String, Object> m = Json.obj(Json.parse(no.body()));
        assertEquals(false, m.get("ready"));
        assertEquals("no raft leader known", m.get("reason"));
        ready.set(new Readiness(true, "", "gw1", 1, 1));
        TestUtil.Resp yes = TestUtil.get(base + "/readyz");
        assertEquals(200, yes.status());
        assertEquals(true, Json.obj(Json.parse(yes.body())).get("ready"));
        assertEquals(405, TestUtil.http("DELETE", base + "/readyz", null).status());
    }

    @Test
    void metricsExposeEverySignalPerBackend() {
        TestUtil.get(base + "/api/x");
        TestUtil.Resp r = TestUtil.get(base + "/metrics");
        assertEquals(200, r.status());
        assertTrue(r.header("Content-Type").startsWith("text/plain"));
        for (String metric : new String[] {"healthy", "ewma_latency_ms", "error_rate", "inflight", "cpu",
                "kernel_penalty", "cost", "requests_total", "failures_total"}) {
            assertTrue(r.body().contains("aegis_backend_" + metric + "{service=\"api\",backend=\"w1\",state=\"closed\"}"), metric);
        }
        assertTrue(r.body().contains("aegis_backend_requests_total{service=\"api\",backend=\"w1\",state=\"closed\"} 1"));
        assertEquals(405, TestUtil.post(base + "/metrics", "").status());
    }

    @Test
    void eventsAreServedAsAJsonArray() {
        router.all().get(0).breaker.forceOpen();
        TestUtil.eventually("event visible", java.time.Duration.ofSeconds(2), () -> {
            TestUtil.Resp r = TestUtil.get(base + "/_aegis/events");
            return r.status() == 200 && DataPlane.parseEvents(r.body()).size() == 1;
        });
        Map<String, Object> e = Json.obj(DataPlane.parseEvents(TestUtil.get(base + "/_aegis/events").body()).get(0));
        assertEquals("breaker", e.get("kind"));
        assertEquals("w1", e.get("backend"));
    }

    @Test
    void everythingElseIsProxiedAndUnknownServicesGet503() {
        TestUtil.Resp ok = TestUtil.get(base + "/api/anything?q=1");
        assertEquals(200, ok.status());
        assertEquals("ok", ok.body());
        assertEquals("w1", ok.header("X-Aegis-Backend"));
        assertEquals(503, TestUtil.get(base + "/nosuch/x").status());
        assertEquals(404, TestUtil.get(base + "/").status());
        // reserved names are not services even though they look like one
        assertEquals(200, TestUtil.get(base + "/healthz").status());
    }

    @Test
    void headRequestsGetHeadersButNoBody() {
        TestUtil.Resp r = TestUtil.http("HEAD", base + "/api/x", null);
        assertEquals(200, r.status());
        assertEquals("", r.body());
    }

    @Test
    void postBodiesAreForwarded() {
        assertEquals(200, TestUtil.post(base + "/api/submit", "{\"x\":1}").status());
    }

    /**
     * Regression: without TCP_NODELAY the JDK server's separate header/body writes interact with delayed ACKs and
     * add ~40 ms to many small keep-alive responses (the benchmark showed 50 ms and 96 ms medians for a 10 ms service).
     */
    @Test
    void smallKeepAliveResponsesAreNotStalledByNagle() {
        List<Long> micros = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long t0 = System.nanoTime();
            assertEquals(200, TestUtil.get(base + "/api/x").status());
            micros.add((System.nanoTime() - t0) / 1000);
        }
        java.util.Collections.sort(micros);
        long medianMs = micros.get(micros.size() / 2) / 1000;
        assertTrue(medianMs < 30, "median latency through the gateway was " + medianMs + " ms for a ~1 ms backend");
    }

    @Test
    void servesManyConcurrentClients() throws Exception {
        List<Thread> ts = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 100; i++) {
            ts.add(Thread.startVirtualThread(() -> {
                if (TestUtil.get(base + "/api/x").status() == 200) {
                    ok.incrementAndGet();
                }
            }));
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(100, ok.get());
    }
}
