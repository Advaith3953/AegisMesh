package aegismesh.breaker;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Circuit breaker with the classic CLOSED → OPEN → HALF-OPEN state machine.
 *
 * <pre>
 *             consecutive failures
 *   CLOSED ───────────────────────────→ OPEN
 *     ↑                                   │
 *     │ enough probe successes            │ open timeout elapsed
 *     │                                   ↓
 *     └────────────────────────────── HALF-OPEN ──(probe fails)──→ OPEN
 * </pre>
 *
 * Every admitted request gets a {@link Ticket} carrying the state "generation" it was admitted in; reporting a
 * result with a ticket from an older generation is ignored, so a slow response from before a trip cannot close or
 * reopen the breaker by accident.
 */
public final class Breaker {
    public enum State {
        CLOSED("closed"), OPEN("open"), HALF_OPEN("half-open");

        private final String label;

        State(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @param failureThreshold consecutive failures that trip the breaker
     * @param openTimeout how long to stay OPEN before probing
     * @param halfOpenMaxProbes concurrent probe requests allowed in HALF-OPEN
     * @param successThreshold probe successes needed to close again
     */
    public record Config(int failureThreshold, Duration openTimeout, int halfOpenMaxProbes, int successThreshold) {
        public static Config defaults() {
            return new Config(5, Duration.ofSeconds(1), 1, 2);
        }
    }

    /** Handed out by {@link #allow()} and given back to {@link #report}/{@link #cancel}. */
    public record Ticket(boolean probe, long generation) {}

    private final Config cfg;
    private final LongSupplier nanoClock;
    private State state = State.CLOSED;
    private int failures;
    private int successes;
    private int probes;
    private long openedAt;
    private long generation;
    private BiConsumer<State, State> onChange;

    public Breaker(Config cfg) {
        this(cfg, System::nanoTime);
    }

    /** @param nanoClock monotonic clock in nanoseconds; tests pass a fake one */
    public Breaker(Config cfg, LongSupplier nanoClock) {
        Duration timeout = cfg.openTimeout();
        this.cfg = new Config(
                cfg.failureThreshold() <= 0 ? 5 : cfg.failureThreshold(),
                timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(1) : timeout,
                cfg.halfOpenMaxProbes() <= 0 ? 1 : cfg.halfOpenMaxProbes(),
                cfg.successThreshold() <= 0 ? 1 : cfg.successThreshold());
        this.nanoClock = nanoClock;
    }

    /** Registers a callback fired asynchronously on every state transition. */
    public synchronized void onChange(BiConsumer<State, State> callback) {
        this.onChange = callback;
    }

    private void transition(State to) {
        State from = state;
        if (from == to) {
            return;
        }
        state = to;
        generation++;
        failures = 0;
        successes = 0;
        probes = 0;
        if (to == State.OPEN) {
            openedAt = nanoClock.getAsLong();
        }
        BiConsumer<State, State> cb = onChange;
        if (cb != null) {
            Thread.startVirtualThread(() -> cb.accept(from, to));
        }
    }

    private void maybeHalfOpen() {
        if (state == State.OPEN && nanoClock.getAsLong() - openedAt >= cfg.openTimeout().toNanos()) {
            transition(State.HALF_OPEN);
        }
    }

    /** Current state; lazily moves OPEN → HALF-OPEN once the open timeout has elapsed. */
    public synchronized State state() {
        maybeHalfOpen();
        return state;
    }

    /** Side-effect-free check used by routers to filter candidates: would {@link #allow()} admit a request now? */
    public synchronized boolean admits() {
        maybeHalfOpen();
        return switch (state) {
            case CLOSED -> true;
            case HALF_OPEN -> probes < cfg.halfOpenMaxProbes();
            case OPEN -> false;
        };
    }

    /** Asks permission to send one request; empty if the breaker refuses. */
    public synchronized Optional<Ticket> allow() {
        maybeHalfOpen();
        if (state == State.CLOSED) {
            return Optional.of(new Ticket(false, generation));
        }
        if (state == State.HALF_OPEN && probes < cfg.halfOpenMaxProbes()) {
            probes++;
            return Optional.of(new Ticket(true, generation));
        }
        return Optional.empty();
    }

    /** Records the outcome of a request admitted by {@link #allow()}. */
    public synchronized void report(Ticket t, boolean success) {
        if (t.generation() != generation) {
            return; // belongs to an earlier state
        }
        if (state == State.CLOSED) {
            if (success) {
                failures = 0;
                return;
            }
            failures++;
            if (failures >= cfg.failureThreshold()) {
                transition(State.OPEN);
            }
        } else if (state == State.HALF_OPEN) {
            if (t.probe() && probes > 0) {
                probes--;
            }
            if (!success) {
                transition(State.OPEN);
                return;
            }
            successes++;
            if (successes >= cfg.successThreshold()) {
                transition(State.CLOSED);
            }
        }
    }

    /**
     * Releases a ticket without recording an outcome. Use it when the request ended for a reason that says nothing
     * about the backend (for example the client disconnected): counting that as a success could wrongly close a
     * half-open breaker, and counting it as a failure could wrongly open it.
     */
    public synchronized void cancel(Ticket t) {
        if (t.generation() == generation && state == State.HALF_OPEN && t.probe() && probes > 0) {
            probes--;
        }
    }

    /** Trips immediately (an active health check declared the backend dead). */
    public synchronized void forceOpen() {
        if (state != State.OPEN) {
            transition(State.OPEN);
        }
    }

    /** Closes immediately (an active health check saw recovery). */
    public synchronized void reset() {
        transition(State.CLOSED);
    }
}
