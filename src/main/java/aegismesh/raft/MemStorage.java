package aegismesh.raft;

import aegismesh.raft.Types.PersistentState;
import aegismesh.raft.Types.Storage;
import java.util.ArrayList;

/** Keeps state in memory only; survives a "restart" within one test because the object is reused. */
public final class MemStorage implements Storage {
    private PersistentState state = PersistentState.empty();

    @Override
    public synchronized PersistentState load() {
        return copy(state);
    }

    @Override
    public synchronized void save(PersistentState s) {
        state = copy(s);
    }

    private static PersistentState copy(PersistentState s) {
        return new PersistentState(s.term(), s.votedFor(), new ArrayList<>(s.log()));
    }
}
