package aegismesh.control;

import aegismesh.lb.Spec;
import aegismesh.raft.Types.StateMachine;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The deterministic state machine every replica runs. A command that is malformed or invalid is skipped, and since
 * every replica applies the same checks to the same bytes, they all skip it identically.
 */
public final class Fsm implements StateMachine {
    private long version;
    private final Map<String, Spec> backends = new HashMap<>();
    private final Map<String, String> policies = new HashMap<>();
    private final Consumer<State> onChange;

    public Fsm(Consumer<State> onChange) {
        this.onChange = onChange;
    }

    @Override
    public void apply(long index, String raw) {
        Command c;
        try {
            c = Command.fromJson(raw);
            c.validate();
        } catch (RuntimeException | ControlException e) {
            return;
        }
        State snap;
        synchronized (this) {
            switch (c.op()) {
                case "add_backend" -> backends.put(c.backend().id(), c.backend());
                case "remove_backend" -> backends.remove(c.id());
                case "set_policy" -> policies.put(c.service(), c.policy());
                default -> { }
            }
            version++;
            snap = snapshotLocked();
        }
        if (onChange != null) {
            onChange.accept(snap);
        }
    }

    private State snapshotLocked() {
        return new State(version, new HashMap<>(backends), new HashMap<>(policies));
    }

    public synchronized State snapshot() {
        return snapshotLocked();
    }
}
