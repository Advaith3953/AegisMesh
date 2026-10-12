package aegismesh.raft;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Wire and storage types of the Raft implementation. */
public final class Types {
    private Types() {}

    /** One log entry. {@code cmd == null} marks the no-op a new leader appends to commit earlier terms. */
    public record Entry(long term, long index, String cmd) {}

    public record RequestVoteArgs(long term, String candidateId, long lastLogIndex, long lastLogTerm) {}

    public record RequestVoteReply(long term, boolean voteGranted) {}

    public record AppendEntriesArgs(long term, String leaderId, long prevLogIndex, long prevLogTerm,
                                    List<Entry> entries, long leaderCommit) {}

    /** {@code conflictIndex} tells the leader where to retry from after a failed consistency check. */
    public record AppendEntriesReply(long term, boolean success, long conflictIndex) {}

    /** State that must survive a restart (Raft paper figure 2): current term, vote, and the log without its sentinel. */
    public record PersistentState(long term, String votedFor, List<Entry> log) {
        public static PersistentState empty() {
            return new PersistentState(0, "", List.of());
        }
    }

    public record Status(String id, Role role, long term, String leader, long commitIndex, long lastApplied,
                         long lastIndex) {}

    /** Moves Raft RPCs between nodes. Implementations throw {@link IOException} when the peer is unreachable. */
    public interface Transport {
        RequestVoteReply requestVote(String to, RequestVoteArgs a, Duration timeout) throws IOException;

        AppendEntriesReply appendEntries(String to, AppendEntriesArgs a, Duration timeout) throws IOException;
    }

    /** The replicated state machine; {@code apply} is called once per committed command, in log order. */
    public interface StateMachine {
        void apply(long index, String cmd);
    }

    public interface Storage {
        PersistentState load() throws IOException;

        void save(PersistentState s) throws IOException;
    }
}
