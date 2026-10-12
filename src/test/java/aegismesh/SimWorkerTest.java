package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.sim.Worker;
import aegismesh.util.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SimWorkerTest {
    private final List<Worker> ws = new ArrayList<>();

    @AfterEach
    void tearDown() {
        ws.forEach(Worker::kill);
    }

    private String start(Worker.Config c) throws Exception {
        Worker w = new Worker("w", c);
        w.start("127.0.0.1:0");
        ws.add(w);
        return "http://" + w.addr();
    }

    private Worker last() {
        return ws.get(ws.size() - 1);
    }

    @Test
    void servesAndIdentifiesItself() throws Exception {
        String url = start(new Worker.Config().withServiceMs(1));
        TestUtil.Resp r = TestUtil.get(url + "/anything");
        assertEquals(200, r.status());
        assertEquals("ok", r.body());
        assertEquals("w", r.header("X-Worker"));
        assertEquals(1, last().served.get());
    }

    @Test
    void defaultsAreAppliedToZeroValues() {
        Worker.Config c = new Worker.Config();
        assertEquals(8, c.capacity());
        assertEquals(10, c.serviceMs());
        assertEquals(1, c.serviceMul());
        assertEquals(-1, c.cpu());
    }

    @Test
    void injectedErrorsCanBeAppliedAndCleared() throws Exception {
        String url = start(new Worker.Config().withServiceMs(1));
        last().update(c -> c.withErrorRate(1));
        assertEquals(500, TestUtil.get(url + "/x").status());
        last().update(c -> c.withErrorRate(0));
        assertEquals(200, TestUtil.get(url + "/x").status());
    }

    @Test
    void extraLatencyAndLossStallsAreApplied() throws Exception {
        String url = start(new Worker.Config().withServiceMs(1).withExtraMs(80));
        long t0 = System.nanoTime();
        TestUtil.get(url + "/x");
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 70);
        last().update(c -> c.withExtraMs(0).withLossRate(1));
        t0 = System.nanoTime();
        TestUtil.get(url + "/x");
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 180, "a loss event stalls ~200 ms");
    }

    @Test
    void healthReportsCpuFromSlotUsageOrAnOverride() throws Exception {
        String url = start(new Worker.Config().withServiceMs(1));
        Map<String, Object> h = Json.obj(Json.parse(TestUtil.get(url + "/healthz").body()));
        assertEquals("ok", h.get("status"));
        assertEquals(0.0, ((Number) h.get("cpu")).doubleValue(), 1e-9);
        last().update(c -> c.withCpu(0.9));
        h = Json.obj(Json.parse(TestUtil.get(url + "/healthz").body()));
        assertEquals(0.9, ((Number) h.get("cpu")).doubleValue(), 1e-9);
    }

    @Test
    void capacityLimitsConcurrencyAndQueuesTheRest() throws Exception {
        String url = start(new Worker.Config().withCapacity(2).withServiceMs(80).withServiceMul(1));
        long t0 = System.nanoTime();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ts.add(Thread.startVirtualThread(() -> TestUtil.get(url + "/x")));
        }
        for (Thread t : ts) {
            t.join();
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 140, "4 requests through 2 slots need two waves");
    }

    @Test
    void killDropsTheServerAndRestartKeepsTheAddress() throws Exception {
        String url = start(new Worker.Config().withServiceMs(1));
        String addr = last().addr();
        last().kill();
        last().kill(); // idempotent
        assertEquals(-1, TestUtil.get(url + "/x").status());
        last().restart();
        last().restart(); // idempotent
        assertEquals(addr, last().addr());
        assertEquals(200, TestUtil.get(url + "/x").status());
    }

    @Test
    void faultEndpointValidatesMethodAndBody() throws Exception {
        String url = start(new Worker.Config().withServiceMs(1));
        TestUtil.Resp get = TestUtil.get(url + "/_fault");
        assertEquals(405, get.status());
        assertEquals("POST", get.header("Allow"));
        assertEquals(400, TestUtil.post(url + "/_fault", "not json").status());
        assertEquals(400, TestUtil.post(url + "/_fault", "[1]").status());
        assertEquals(400, TestUtil.post(url + "/_fault", "{\"extra_ms\":\"slow\"}").status());
        assertEquals(200, TestUtil.post(url + "/_fault", "{\"error_rate\":1,\"cpu\":0.7,\"service_mul\":2}").status());
        assertEquals(500, TestUtil.get(url + "/x").status());
        assertEquals(0.7, last().config().cpu(), 1e-9);
        assertEquals(2.0, last().config().serviceMul(), 1e-9);
    }

    @Test
    void startingTwiceOrOnABadAddressFails() throws Exception {
        start(new Worker.Config());
        assertThrows(IllegalStateException.class, () -> last().start("127.0.0.1:0"));
        assertThrows(java.io.IOException.class, () -> new Worker("x", new Worker.Config()).start(last().addr()));
        assertThrows(IllegalArgumentException.class, () -> new Worker("y", new Worker.Config()).start("not-an-address"));
        assertThrows(IllegalArgumentException.class, () -> new Worker("y", new Worker.Config()).start("host:99999"));
        assertFalse(last().addr().isEmpty());
    }
}
