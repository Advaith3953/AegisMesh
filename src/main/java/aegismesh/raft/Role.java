package aegismesh.raft;

public enum Role {
    FOLLOWER, CANDIDATE, LEADER;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
