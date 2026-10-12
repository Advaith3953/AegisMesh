package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.breaker.Breaker;
import aegismesh.lb.Backend;
import aegismesh.lb.EventLog;
import aegismesh.lb.HealthChecker;
import aegismesh.lb.Router;
import aegismesh.lb.ScoreConfig;
import aegismesh.lb.Spec;
import aegismesh.sim.Worker;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HealthCheckerTest {
    private Worker w;
    private HttpServer custom;
    private HealthChecker hc;

    @AfterEach
    void tearDown() {
        if (hc != null) {
            hc.stop();
        }
        if (w != null) {
            w.kill();
        }
        if (custom != null) {
            custom.stop(0);
        }
    }

    private Router routerFor(String addr) {
        Router r = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), "adaptive");
        r.reconcile(List.of(new Spec("b", "api", addr, 0)));
        return r;
    }

    private Worker startWorker() throws IOException {
        w = new Worker("b", new Worker.Config().withServiceMs(1));
        w.start("127.0.0.1:0");
        return w;
    }

    private static boolean has(Router r, String kind) {
        return r.events.all().stream().anyMatch(e -> e.kind().equals(kind) && e.backend().equals("b"));
    }

    @Test
    void ejectsAfterTwoFailedRoundsAndReintroducesAfterTwoGoodOnes() throws Exception {
        startWorker();
        Router r = routerFor(w.addr());
        Backend b = r.all().get(0);
        hc = new HealthChecker(r, Duration.ofMillis(100));
        hc.round();
        hc.round();
        assertTrue(b.healthy());
        w.kill();
        hc.round();
        assertTrue(b.healthy(), "one failure is not enough");
        hc.round();
        assertFalse(b.healthy());
        assertEquals(Breaker.State.OPEN, b.breaker.state());
        assertTrue(has(r, "ejected"));
        w.restart();
        hc.round();
        assertFalse(b.healthy(), "one success is not enough");
        hc.round();
        assertTrue(b.healthy());
        assertEquals(Breaker.State.CLOSED, b.breaker.state());
        assertTrue(has(r, "reintroduced"));
    }

    @Test
    void carriesTheBackendsReportedCpuIntoTheScore() throws Exception {
        startWorker();
        w.update(c -> c.withCpu(0.9));
        Router r = routerFor(w.addr());
        hc = new HealthChecker(r, Duration.ofMillis(100));
        hc.round();
        assertEquals(0.9, r.all().get(0).snapshot(r.score, System.nanoTime()).cpu(), 1e-9);
    }

    @Test
    void backgroundLoopEjectsAndRecoversWithoutManualRounds() throws Exception {
        startWorker();
        Router r = routerFor(w.addr());
        Backend b = r.all().get(0);
        hc = new HealthChecker(r, Duration.ofMillis(50));
        hc.start();
        hc.start(); // idempotent
        w.kill();
        TestUtil.eventually("ejection", Duration.ofSeconds(5), () -> !b.healthy());
        w.restart();
        TestUtil.eventually("reintroduction", Duration.ofSeconds(5), b::healthy);
        hc.stop();
        hc.stop(); // idempotent
    }

    @Test
    void aNon200OrNonJsonAnswerCountsAsUnhealthy() throws Exception {
        for (String mode : new String[] {"500", "html", "slow"}) {
            custom = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            custom.createContext("/", ex -> {
                try {
                    if (mode.equals("slow")) {
                        Thread.sleep(1500);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = mode.equals("html") ? "<html>hi</html>".getBytes() : new byte[0];
                try {
                    ex.sendResponseHeaders(mode.equals("500") ? 500 : 200, body.length == 0 ? -1 : body.length);
                    if (body.length > 0) {
                        ex.getResponseBody().write(body);
                    }
                } catch (IOException ignored) {
                    // the probe may have given up already
                }
                ex.close();
            });
            custom.start();
            Router r = routerFor("127.0.0.1:" + custom.getAddress().getPort());
            hc = new HealthChecker(r, Duration.ofMillis(100));
            hc.round();
            hc.round();
            assertFalse(r.all().get(0).healthy(), mode);
            custom.stop(0);
        }
    }

    @Test
    void probesAllBackendsConcurrentlyAndSurvivesAnEmptyRouter() throws Exception {
        Router empty = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), "adaptive");
        new HealthChecker(empty, Duration.ofMillis(50)).round();
        Router r = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), "adaptive");
        java.util.ArrayList<Spec> specs = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            specs.add(new Spec("dead" + i, "api", "127.0.0.1:1", 0));
        }
        r.reconcile(specs);
        hc = new HealthChecker(r, Duration.ofMillis(300));
        long t0 = System.nanoTime();
        hc.round();
        hc.round();
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 2000, "probes must run in parallel");
        assertTrue(r.all().stream().noneMatch(Backend::healthy));
    }
}
