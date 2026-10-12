package aegismesh.raft;

import aegismesh.raft.Types.AppendEntriesArgs;
import aegismesh.raft.Types.AppendEntriesReply;
import aegismesh.raft.Types.RequestVoteArgs;
import aegismesh.raft.Types.RequestVoteReply;
import aegismesh.raft.Types.Transport;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory "network" for tests: nodes can be crashed or partitioned, and RPCs can be delayed. */
public final class MemNetwork {
    private final Map<String, Node> nodes = new HashMap<>();
    private final Set<String> down = new HashSet<>();
    private final Set<String> cut = new HashSet<>();
    private volatile Duration delay = Duration.ZERO;

    public synchronized void register(String id, Node n) {
        nodes.put(id, n);
    }

    public synchronized void setDown(String id, boolean isDown) {
        if (isDown) {
            down.add(id);
        } else {
            down.remove(id);
        }
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public synchronized void cut(String a, String b, boolean isCut) {
        if (isCut) {
            cut.add(pair(a, b));
        } else {
            cut.remove(pair(a, b));
        }
    }

    /** Cuts (or heals) every link between {@code id} and each of {@code others}. */
    public void isolate(String id, List<String> others, boolean isolated) {
        for (String o : others) {
            if (!o.equals(id)) {
                cut(id, o, isolated);
            }
        }
    }

    public void setDelay(Duration d) {
        this.delay = d;
    }

    public Transport transportFor(String from) {
        return new Transport() {
            @Override
            public RequestVoteReply requestVote(String to, RequestVoteArgs a, Duration timeout) throws IOException {
                return target(from, to, timeout).handleRequestVote(a);
            }

            @Override
            public AppendEntriesReply appendEntries(String to, AppendEntriesArgs a, Duration timeout)
                    throws IOException {
                return target(from, to, timeout).handleAppendEntries(a);
            }
        };
    }

    private Node target(String from, String to, Duration timeout) throws IOException {
        Node n;
        boolean blocked;
        synchronized (this) {
            n = nodes.get(to);
            blocked = n == null || down.contains(from) || down.contains(to) || cut.contains(pair(from, to));
        }
        if (blocked) {
            throw new IOException("raft: peer unreachable");
        }
        Duration d = delay;
        if (!d.isZero()) {
            if (d.compareTo(timeout) > 0) {
                throw new IOException("raft: rpc timed out");
            }
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        return n;
    }
}
