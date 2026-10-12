package aegismesh.lb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.breaker.Breaker;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScoringTest {
    private static final ScoreConfig CFG = ScoreConfig.defaults();
    private static final long T0 = 10_000_000_000L;

    private static Backend backend() {
        return new Backend("a", "svc", "127.0.0.1:1", Breaker.Config.defaults());
    }

    private static void observe(Backend b, double ms, boolean ok, long at) {
        b.observe(CFG, (long) (ms * 1e6), ok, at);
    }

    @Test
    void anUnseenBackendUsesTheOptimisticPrior() {
        assertEquals(CFG.initialMs(), backend().cost(CFG, T0), 1e-9);
    }

    @Test
    void costNeverDropsBelowTheLatencyFloor() {
        Backend b = backend();
        observe(b, 0, true, T0);
        assertEquals(CFG.floorMs(), b.cost(CFG, T0), 1e-9);
    }

    @Test
    void firstSampleSeedsTheEwmaThenLaterSamplesSmoothIt() {
        Backend b = backend();
        observe(b, 100, true, T0);
        assertEquals(100, b.snapshot(CFG, T0).ewmaMs(), 1e-9);
        observe(b, 200, true, T0);
        assertEquals(0.2 * 200 + 0.8 * 100, b.snapshot(CFG, T0).ewmaMs(), 1e-9);
        observe(b, 100, true, T0); // a spike is absorbed gradually, not instantly
        assertTrue(b.snapshot(CFG, T0).ewmaMs() > 100);
    }

    @Test
    void costGrowsWithInflightRequests() {
        Backend b = backend();
        observe(b, 10, true, T0);
        double idle = b.cost(CFG, T0);
        b.inflight.addAndGet(3);
        assertEquals(idle * 4, b.cost(CFG, T0), 1e-9);
        assertEquals(3, b.inflight());
    }

    @Test
    void errorsRaiseTheCostAndRecoverWithSuccesses() {
        Backend b = backend();
        observe(b, 10, true, T0);
        double healthy = b.cost(CFG, T0);
        observe(b, 10, false, T0);
        assertTrue(b.cost(CFG, T0) > healthy * 3);
        assertTrue(b.snapshot(CFG, T0).errRate() > 0);
        for (int i = 0; i < 40; i++) {
            observe(b, 10, true, T0);
        }
        assertEquals(0.0, b.snapshot(CFG, T0).errRate(), 0.001);
    }

    @Test
    void recentFailuresDecayWithTime() {
        Backend b = backend();
        observe(b, 10, false, T0);
        double now = b.snapshot(CFG, T0).recentFailures();
        assertEquals(1.0, now, 1e-9);
        long later = T0 + Duration.ofSeconds(5).toNanos(); // one half-life
        assertEquals(0.5, b.snapshot(CFG, later).recentFailures(), 0.01);
        assertEquals(0.0, b.snapshot(CFG, T0 + Duration.ofSeconds(120).toNanos()).recentFailures(), 0.001);
    }

    @Test
    void aStaleTimestampNeverDecaysBackwards() {
        Backend b = backend();
        observe(b, 10, false, T0 + 1_000_000_000L);
        observe(b, 10, true, T0); // concurrent caller with an older clock reading
        assertTrue(b.snapshot(CFG, T0).recentFailures() > 0.9);
    }

    @Test
    void onlyCpuAboveHalfCounts() {
        Backend b = backend();
        observe(b, 10, true, T0);
        double base = b.cost(CFG, T0);
        b.setCpu(0.5);
        assertEquals(base, b.cost(CFG, T0), 1e-9);
        b.setCpu(1.0);
        assertEquals(base * (1 + CFG.cpuWeight()), b.cost(CFG, T0), 1e-9);
        b.setCpu(7); // clamped
        assertEquals(1.0, b.snapshot(CFG, T0).cpu(), 1e-9);
        b.setCpu(-3);
        assertEquals(0.0, b.snapshot(CFG, T0).cpu(), 1e-9);
    }

    @Test
    void kernelPenaltyRaisesCostAndIsClampedAtZero() {
        Backend b = backend();
        observe(b, 10, true, T0);
        double base = b.cost(CFG, T0);
        b.setKernelPenalty(2);
        assertEquals(base * (1 + 2 * CFG.kernelWeight()), b.cost(CFG, T0), 1e-9);
        b.setKernelPenalty(-5);
        assertEquals(base, b.cost(CFG, T0), 1e-9);
    }

    @Test
    void weightDividesTheCostAndIsClampedToOne() {
        Backend b = backend();
        observe(b, 10, true, T0);
        double base = b.cost(CFG, T0);
        b.setWeight(4);
        assertEquals(base / 4, b.cost(CFG, T0), 1e-9);
        b.setWeight(0);
        assertEquals(1, b.weight());
        b.setWeight(-9);
        assertEquals(1, b.weight());
        assertEquals(base, b.cost(CFG, T0), 1e-9);
    }

    /** Regression: weight used to be written by the config thread and read by the scorer without a lock. */
    @Test
    void weightCanChangeWhileRequestsAreBeingScored() throws Exception {
        Backend b = backend();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 200_000; i++) {
                b.setWeight(i % 5);
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 200_000; i++) {
                double c = b.cost(CFG, T0);
                if (!(c > 0) || Double.isNaN(c)) {
                    throw new AssertionError("bad cost " + c);
                }
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
    }

    @Test
    void probesEjectAfterConsecutiveFailuresAndReviveAfterConsecutivePasses() {
        Backend b = backend();
        assertEquals(0, b.recordProbe(false, 0, 2, 2));
        assertEquals(1, b.recordProbe(false, 0, 2, 2), "second failure ejects");
        assertTrue(b.healthy()); // recordProbe only decides; the checker applies it
        b.setHealthy(false);
        assertEquals(0, b.recordProbe(false, 0, 2, 2), "an already ejected backend is not ejected again");
        assertEquals(0, b.recordProbe(true, 0.3, 2, 2));
        assertEquals(0, b.recordProbe(false, 0, 2, 2), "a failure resets the pass streak");
        assertEquals(0, b.recordProbe(true, 0.3, 2, 2));
        assertEquals(-1, b.recordProbe(true, 0.3, 2, 2), "second consecutive pass revives");
        assertFalse(b.healthy());
    }

    @Test
    void snapshotReportsEverySignal() {
        Backend b = backend();
        observe(b, 20, true, T0);
        b.requests.set(5);
        b.failuresTotal.set(1);
        Signals s = b.snapshot(CFG, T0);
        assertEquals("a", s.id());
        assertEquals("svc", s.service());
        assertEquals("127.0.0.1:1", s.addr());
        assertEquals("closed", s.state());
        assertTrue(s.healthy());
        assertEquals(5, s.requests());
        assertEquals(1, s.failures());
        assertEquals(s.cost(), b.cost(CFG, T0), 1e-9);
    }
}
