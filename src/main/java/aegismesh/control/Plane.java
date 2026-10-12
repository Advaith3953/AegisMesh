package aegismesh.control;

import aegismesh.lb.Backend;
import aegismesh.lb.Readiness;
import aegismesh.lb.Router;
import aegismesh.raft.Node;
import aegismesh.raft.RaftException;
import aegismesh.raft.Types.Status;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Control plane of one gateway: submits commands to Raft (forwarding to the leader when needed) and reports status. */
public final class Plane {
    public final Node node;
    public final Fsm fsm;
    private final Map<String, String> addrs; // node id -> control address
    private final HttpClient client;

    public Plane(Node node, Fsm fsm, Map<String, String> addrs) {
        this.node = node;
        this.fsm = fsm;
        this.addrs = addrs;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    public Map<String, String> addrs() {
        return addrs;
    }

    /** Validates {@code c}, commits it through Raft (forwarding to the leader if this node is not it). */
    public void submit(Command c, Duration timeout) throws ControlException {
        submit(c, timeout, false);
    }

    void submit(Command c, Duration timeout, boolean forwarded) throws ControlException {
        c.validate();
        String raw = c.toJson();
        try {
            node.propose(raw, timeout);
            return;
        } catch (RaftException.NotLeader nl) {
            if (forwarded) {
                throw new ControlException("forwarded to a non-leader (leader moved): " + nl.getMessage(), false);
            }
            forward(raw, nl.leader(), timeout);
        } catch (RaftException e) {
            throw new ControlException(e.getMessage(), false);
        }
    }

    private void forward(String raw, String leader, Duration timeout) throws ControlException {
        if (leader.isEmpty()) {
            throw new ControlException("no leader elected yet", false);
        }
        String addr = addrs.get(leader);
        if (addr == null) {
            throw new ControlException("unknown leader '" + leader + "'", false);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + addr + "/admin/command"))
                .timeout(timeout.plusSeconds(1)).header("X-Aegis-Forwarded", "1")
                .POST(HttpRequest.BodyPublishers.ofString(raw)).build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ControlException("leader rejected command: HTTP " + resp.statusCode() + " "
                        + resp.body().trim(), resp.statusCode() == 400);
            }
        } catch (IOException e) {
            throw new ControlException("could not reach leader " + leader + ": " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlException("interrupted", false);
        }
    }

    /** Like {@link #submit} but keeps retrying transient failures (no leader yet, leader moved) until {@code total}. */
    public void submitRetry(Command c, Duration total) throws ControlException {
        long deadline = System.nanoTime() + total.toNanos();
        ControlException last = null;
        while (System.nanoTime() < deadline) {
            try {
                submit(c, Duration.ofSeconds(2));
                return;
            } catch (ControlException e) {
                if (e.invalid()) {
                    throw e;
                }
                last = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ControlException("timed out (last error: " + (last == null ? "none" : last.getMessage()) + ")", false);
    }

    /** Readiness for {@code /readyz}: a leader is known (quorum reachable) and at least one backend is healthy. */
    public Readiness readiness(Router router) {
        Status st = node.status();
        int backends = 0;
        int healthy = 0;
        long now = System.nanoTime();
        for (Backend b : router.all()) {
            backends++;
            if (b.snapshot(router.score, now).healthy()) {
                healthy++;
            }
        }
        String reason = null;
        boolean ready = false;
        if (st.leader().isEmpty()) {
            reason = "no raft leader known (no quorum yet)";
        } else if (backends == 0) {
            reason = "no backends registered";
        } else if (healthy == 0) {
            reason = "no healthy backend";
        } else {
            ready = true;
        }
        return new Readiness(ready, reason, st.leader(), backends, healthy);
    }
}
