package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.breaker.Breaker;
import aegismesh.breaker.Breaker.Config;
import aegismesh.breaker.Breaker.State;
import aegismesh.breaker.Breaker.Ticket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BreakerTest {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private Breaker b;

    @BeforeEach
    void setUp() {
        b = new Breaker(new Config(3, Duration.ofSeconds(1), 1, 2), now::get);
    }

    private void advance(long ms) {
        now.addAndGet(ms * 1_000_000L);
    }

    private Ticket allow() {
        return b.allow().orElseThrow();
    }

    private void trip() {
        for (int i = 0; i < 3; i++) {
            b.report(allow(), false);
        }
    }

    @Test
    void staysClosedBelowTheFailureThreshold() {
        b.report(allow(), false);
        b.report(allow(), false);
        assertEquals(State.CLOSED, b.state());
    }

    @Test
    void aSuccessResetsTheFailureStreak() {
        b.report(allow(), false);
        b.report(allow(), false);
        b.report(allow(), true);
        b.report(allow(), false);
        b.report(allow(), false);
        assertEquals(State.CLOSED, b.state());
    }

    @Test
    void opensAfterConsecutiveFailuresAndRefuses() {
        trip();
        assertEquals(State.OPEN, b.state());
        assertTrue(b.allow().isEmpty());
        assertFalse(b.admits());
    }

    @Test
    void becomesHalfOpenAfterTheTimeoutAndLimitsProbes() {
        trip();
        advance(999);
        assertEquals(State.OPEN, b.state());
        advance(2);
        assertEquals(State.HALF_OPEN, b.state());
        assertTrue(b.admits());
        Ticket probe = allow();
        assertTrue(probe.probe());
        assertFalse(b.admits(), "only one probe may be in flight");
        assertTrue(b.allow().isEmpty());
    }

    @Test
    void closesAfterEnoughProbeSuccesses() {
        trip();
        advance(1001);
        b.report(allow(), true);
        assertEquals(State.HALF_OPEN, b.state());
        b.report(allow(), true);
        assertEquals(State.CLOSED, b.state());
    }

    @Test
    void aFailedProbeReopensImmediately() {
        trip();
        advance(1001);
        b.report(allow(), false);
        assertEquals(State.OPEN, b.state());
        advance(500);
        assertEquals(State.OPEN, b.state(), "the open timeout restarts from the new trip");
    }

    @Test
    void lateResultsFromAnEarlierStateAreIgnored() {
        Ticket old = allow(); // admitted while closed
        trip();
        advance(1001);
        assertEquals(State.HALF_OPEN, b.state());
        b.report(old, true);
        b.report(old, true);
        assertEquals(State.HALF_OPEN, b.state(), "a stale success must not close the breaker");
        Ticket probe = allow();
        b.report(probe, false);
        assertEquals(State.OPEN, b.state());
        b.report(old, true);
        assertEquals(State.OPEN, b.state());
    }

    @Test
    void cancelFreesTheProbeSlotWithoutChangingState() {
        trip();
        advance(1001);
        Ticket probe = allow();
        assertTrue(b.allow().isEmpty());
        b.cancel(probe);
        assertEquals(State.HALF_OPEN, b.state());
        assertTrue(b.allow().isPresent(), "the slot must be reusable after a cancel");
    }

    @Test
    void cancelOfAStaleOrNonProbeTicketIsHarmless() {
        Ticket closedEra = allow();
        trip();
        b.cancel(closedEra);
        assertEquals(State.OPEN, b.state());
        advance(1001);
        Ticket p = allow();
        b.cancel(p);
        b.cancel(p); // double cancel must not free a slot that is not held
        Ticket again = allow();
        assertTrue(again.probe());
        assertTrue(b.allow().isEmpty());
    }

    @Test
    void forceOpenAndResetOverrideTheStateMachine() {
        b.forceOpen();
        assertEquals(State.OPEN, b.state());
        b.forceOpen(); // idempotent
        b.reset();
        assertEquals(State.CLOSED, b.state());
        assertTrue(b.allow().isPresent());
    }

    @Test
    void admitsHasNoSideEffects() {
        trip();
        advance(1001);
        for (int i = 0; i < 5; i++) {
            assertTrue(b.admits());
        }
        assertTrue(b.allow().isPresent(), "admits() must not consume the probe slot");
    }

    @Test
    void invalidConfigurationFallsBackToDefaults() {
        Breaker d = new Breaker(new Config(0, Duration.ZERO, 0, 0), now::get);
        for (int i = 0; i < 4; i++) {
            d.report(d.allow().orElseThrow(), false);
        }
        assertEquals(State.CLOSED, d.state());
        d.report(d.allow().orElseThrow(), false);
        assertEquals(State.OPEN, d.state());
        advance(1001);
        assertEquals(State.HALF_OPEN, d.state());
        assertEquals(State.CLOSED, new Breaker(new Config(1, null, 1, 1), now::get).state()); // null timeout: no NPE
        assertEquals(5, Config.defaults().failureThreshold());
    }

    @Test
    void notifiesEveryTransition() throws Exception {
        BlockingQueue<String> seen = new ArrayBlockingQueue<>(10);
        b.onChange((from, to) -> seen.add(from + "->" + to));
        trip();
        assertEquals("closed->open", seen.poll(2, TimeUnit.SECONDS));
        advance(1001);
        b.state();
        assertEquals("open->half-open", seen.poll(2, TimeUnit.SECONDS));
        b.reset();
        assertEquals("half-open->closed", seen.poll(2, TimeUnit.SECONDS));
    }

    @Test
    void stateNamesAreReadable() {
        assertEquals("closed", State.CLOSED.toString());
        assertEquals("open", State.OPEN.toString());
        assertEquals("half-open", State.HALF_OPEN.toString());
    }

    @Test
    void survivesConcurrentUseWithoutLosingTrack() throws Exception {
        Breaker c = new Breaker(new Config(1000, Duration.ofSeconds(1), 4, 2));
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            int id = i;
            ts[i] = new Thread(() -> {
                for (int k = 0; k < 5000; k++) {
                    boolean ok = (k + id) % 7 != 0;
                    c.allow().ifPresent(t -> c.report(t, ok));
                    c.admits();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(State.CLOSED, c.state()); // failures are never 1000 in a row
    }
}
