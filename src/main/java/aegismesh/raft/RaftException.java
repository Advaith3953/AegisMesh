package aegismesh.raft;

/** Why a proposal did not (or may not have) become committed. */
public class RaftException extends Exception {
    private static final long serialVersionUID = 1L;

    public RaftException(String message) {
        super(message);
    }

    /** This node is not the leader; {@link #leader()} is the leader it knows of, or empty. */
    public static final class NotLeader extends RaftException {
        private static final long serialVersionUID = 1L;
        private final String leader;

        public NotLeader(String leader) {
            super(leader.isEmpty() ? "raft: not the leader (no leader known)"
                    : "raft: not the leader (leader is " + leader + ")");
            this.leader = leader;
        }

        public String leader() {
            return leader;
        }
    }

    /** Leadership changed before the entry was confirmed: it may or may not be committed by the next leader. */
    public static final class LeadershipLost extends RaftException {
        private static final long serialVersionUID = 1L;

        public LeadershipLost() {
            super("raft: leadership lost before the command was confirmed (outcome unknown)");
        }
    }

    public static final class Stopped extends RaftException {
        private static final long serialVersionUID = 1L;

        public Stopped() {
            super("raft: node stopped");
        }
    }

    public static final class Timeout extends RaftException {
        private static final long serialVersionUID = 1L;

        public Timeout() {
            super("raft: timed out waiting for the command to commit (outcome unknown)");
        }
    }
}
