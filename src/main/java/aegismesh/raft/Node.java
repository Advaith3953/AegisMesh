package aegismesh.raft;

import aegismesh.raft.Types.AppendEntriesArgs;
import aegismesh.raft.Types.AppendEntriesReply;
import aegismesh.raft.Types.Entry;
import aegismesh.raft.Types.PersistentState;
import aegismesh.raft.Types.RequestVoteArgs;
import aegismesh.raft.Types.RequestVoteReply;
import aegismesh.raft.Types.StateMachine;
import aegismesh.raft.Types.Status;
import aegismesh.raft.Types.Storage;
import aegismesh.raft.Types.Transport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One Raft consensus node: leader election, log replication, durable state and an apply loop that feeds committed
 * commands to a {@link StateMachine}. Membership is static (no membership changes) and there are no snapshots.
 *
 * <p>All protocol state is guarded by one monitor ({@code mu}). The state machine and the network are never called
 * while holding it, except for the persist call (which must complete before any reply is sent).
 */
public final class Node {
    /** Construction parameters. Durations default to a 50 ms heartbeat and 250–500 ms election timeout. */
    public static final class Config {
        public final String id;
        public final List<String> peers; // every member including id
        public final Transport transport;
        public final StateMachine fsm;
        public Storage storage; // null = in-memory only
        public Duration heartbeat = Duration.ofMillis(50);
        public Duration electionMin = Duration.ofMillis(250);
        public Duration electionMax = Duration.ofMillis(500);

        public Config(String id, List<String> peers, Transport transport, StateMachine fsm) {
            this.id = id;
            this.peers = List.copyOf(peers);
            this.transport = transport;
            this.fsm = fsm;
        }
    }

    private record Waiter(long term, CompletableFuture<Void> done) {}

    private final Config cfg;
    private final Random rng;
    private final Object mu = new Object();

    private Role role = Role.FOLLOWER;
    private long term;
    private String votedFor = "";
    private final List<Entry> log = new ArrayList<>(); // log.get(0) is a sentinel {0,0}
    private String leader = "";

    private long commitIndex;
    private long lastApplied;
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();
    private final Map<String, Boolean> replicating = new HashMap<>();

    private long electionDeadline;
    private long lastHeartbeat;
    private final Map<Long, Waiter> waiters = new HashMap<>();

    private final Semaphore applyKick = new Semaphore(0);
    private boolean stopped;
    private String failure;
    private ScheduledExecutorService ticker;
    private ExecutorService pool;
    private Thread applier;

    public Node(Config cfg) throws IOException {
        this.cfg = cfg;
        this.rng = new Random(System.nanoTime() + cfg.id.length() * 7919L);
        log.add(new Entry(0, 0, null));
        if (cfg.storage != null) {
            PersistentState st = cfg.storage.load();
            this.term = st.term();
            this.votedFor = st.votedFor() == null ? "" : st.votedFor();
            long expect = 1;
            for (Entry e : st.log()) {
                if (e.index() != expect++) {
                    throw new IOException("raft state is corrupt: log index " + e.index() + " out of sequence");
                }
                log.add(e);
            }
        }
        resetElectionTimer();
    }

    public String id() {
        return cfg.id;
    }

    public void start() {
        synchronized (mu) {
            if (ticker != null) {
                return;
            }
            pool = Executors.newVirtualThreadPerTaskExecutor();
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "raft-tick-" + cfg.id);
                t.setDaemon(true);
                return t;
            });
            applier = new Thread(this::applyLoop, "raft-apply-" + cfg.id);
            applier.setDaemon(true);
        }
        applier.start();
        ticker.scheduleWithFixedDelay(this::tickSafely, 5, 5, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        ScheduledExecutorService t;
        ExecutorService p;
        Thread a;
        synchronized (mu) {
            if (stopped) {
                return;
            }
            stopped = true;
            failWaiters(new RaftException.Stopped());
            t = ticker;
            p = pool;
            a = applier;
        }
        if (t != null) {
            t.shutdownNow();
        }
        if (p != null) {
            p.shutdownNow();
        }
        applyKick.release();
        if (a != null) {
            a.interrupt();
            try {
                a.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Status status() {
        synchronized (mu) {
            return new Status(cfg.id, role, term, leader, commitIndex, lastApplied, lastIndex());
        }
    }

    /** Non-null once the node has stopped itself because it could not persist its state. */
    public String failure() {
        synchronized (mu) {
            return failure;
        }
    }

    // ------------------------------------------------------------------ helpers (call with mu held)

    private long lastIndex() {
        return log.size() - 1L;
    }

    private long lastTerm() {
        return log.get(log.size() - 1).term();
    }

    private int majority() {
        return cfg.peers.size() / 2 + 1;
    }

    private void persist() {
        if (cfg.storage == null) {
            return;
        }
        try {
            cfg.storage.save(new PersistentState(term, votedFor, new ArrayList<>(log.subList(1, log.size()))));
        } catch (IOException e) {
            // A node that cannot make its vote/log durable must stop acknowledging anything.
            stopped = true;
            failure = "persistent storage failure: " + e.getMessage();
            failWaiters(new RaftException.Stopped());
            System.err.println("raft[" + cfg.id + "]: " + failure);
            throw new UncheckedIOException(e);
        }
    }

    private void resetElectionTimer() {
        long span = cfg.electionMax.toNanos() - cfg.electionMin.toNanos();
        long d = cfg.electionMin.toNanos() + (long) (rng.nextDouble() * span);
        electionDeadline = System.nanoTime() + d;
    }

    private void failWaiters(RaftException ex) {
        for (Waiter w : waiters.values()) {
            w.done().completeExceptionally(ex);
        }
        waiters.clear();
    }

    private void becomeFollower(long newTerm) {
        boolean wasLeader = role == Role.LEADER;
        if (newTerm > term) {
            term = newTerm;
            votedFor = "";
            persist();
        }
        role = Role.FOLLOWER;
        if (wasLeader) {
            failWaiters(new RaftException.LeadershipLost());
        }
    }

    // ------------------------------------------------------------------ timers

    private void tickSafely() {
        try {
            synchronized (mu) {
                if (stopped) {
                    return;
                }
                long now = System.nanoTime();
                if (role == Role.LEADER) {
                    if (now - lastHeartbeat >= cfg.heartbeat.toNanos()) {
                        lastHeartbeat = now;
                        broadcast();
                    }
                } else if (now - electionDeadline > 0) {
                    startElection();
                }
            }
        } catch (RuntimeException e) {
            if (failure == null) {
                System.err.println("raft[" + cfg.id + "]: tick failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ elections

    private void startElection() {
        role = Role.CANDIDATE;
        term++;
        votedFor = cfg.id;
        leader = "";
        persist();
        resetElectionTimer();
        final long electionTerm = term;
        final int[] votes = {1};
        if (votes[0] >= majority()) {
            becomeLeader();
            return;
        }
        RequestVoteArgs args = new RequestVoteArgs(electionTerm, cfg.id, lastIndex(), lastTerm());
        for (String p : cfg.peers) {
            if (p.equals(cfg.id)) {
                continue;
            }
            pool.submit(() -> {
                RequestVoteReply rep;
                try {
                    rep = cfg.transport.requestVote(p, args, cfg.electionMin);
                } catch (IOException e) {
                    return;
                }
                synchronized (mu) {
                    if (stopped || role != Role.CANDIDATE || term != electionTerm) {
                        return; // a newer term or role change made this vote irrelevant
                    }
                    if (rep.term() > term) {
                        becomeFollower(rep.term());
                        resetElectionTimer();
                        return;
                    }
                    if (rep.voteGranted()) {
                        votes[0]++;
                        if (votes[0] >= majority()) {
                            becomeLeader();
                        }
                    }
                }
            });
        }
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leader = cfg.id;
        for (String p : cfg.peers) {
            nextIndex.put(p, lastIndex() + 1);
            matchIndex.put(p, 0L);
            replicating.put(p, false);
        }
        // A fresh no-op in the new term lets the leader commit entries from earlier terms (Raft section 5.4.2).
        log.add(new Entry(term, lastIndex() + 1, null));
        persist();
        matchIndex.put(cfg.id, lastIndex());
        lastHeartbeat = System.nanoTime();
        advanceCommit(); // single-node clusters commit immediately
        broadcast();
    }

    /** RequestVote RPC handler. */
    public RequestVoteReply handleRequestVote(RequestVoteArgs a) {
        synchronized (mu) {
            if (stopped || a.term() < term) {
                return new RequestVoteReply(term, false);
            }
            if (a.term() > term) {
                becomeFollower(a.term());
            }
            boolean upToDate = a.lastLogTerm() > lastTerm()
                    || (a.lastLogTerm() == lastTerm() && a.lastLogIndex() >= lastIndex());
            if ((votedFor.isEmpty() || votedFor.equals(a.candidateId())) && upToDate) {
                votedFor = a.candidateId();
                persist(); // the vote must be durable before it is reported
                resetElectionTimer();
                return new RequestVoteReply(term, true);
            }
            return new RequestVoteReply(term, false);
        }
    }

    // ------------------------------------------------------------------ replication (leader side)

    private void broadcast() {
        for (String p : cfg.peers) {
            if (!p.equals(cfg.id) && !replicating.getOrDefault(p, false)) {
                replicating.put(p, true);
                pool.submit(() -> replicateLoop(p));
            }
        }
    }

    /** Sends AppendEntries to one peer until it is caught up (one loop per peer at a time). */
    private void replicateLoop(String peer) {
        try {
            while (true) {
                final long sentTerm;
                final Entry prev;
                final List<Entry> entries;
                final AppendEntriesArgs args;
                synchronized (mu) {
                    if (stopped || role != Role.LEADER) {
                        return;
                    }
                    sentTerm = term;
                    long next = Math.max(1, nextIndex.getOrDefault(peer, 1L));
                    prev = log.get((int) (next - 1));
                    entries = new ArrayList<>();
                    long last = lastIndex();
                    if (last >= next) {
                        long end = Math.min(last, next + 63); // at most 64 entries per RPC
                        entries.addAll(log.subList((int) next, (int) end + 1));
                    }
                    args = new AppendEntriesArgs(sentTerm, cfg.id, prev.index(), prev.term(), entries, commitIndex);
                }
                AppendEntriesReply rep;
                try {
                    rep = cfg.transport.appendEntries(peer, args, cfg.heartbeat.multipliedBy(4));
                } catch (IOException e) {
                    return; // the next heartbeat tries again
                }
                synchronized (mu) {
                    if (stopped || role != Role.LEADER || term != sentTerm) {
                        return;
                    }
                    if (rep.term() > term) {
                        becomeFollower(rep.term());
                        resetElectionTimer();
                        return;
                    }
                    if (rep.success()) {
                        long m = prev.index() + entries.size();
                        if (m > matchIndex.getOrDefault(peer, 0L)) {
                            matchIndex.put(peer, m);
                        }
                        nextIndex.put(peer, matchIndex.get(peer) + 1);
                        advanceCommit();
                        if (lastIndex() < nextIndex.get(peer)) {
                            return;
                        }
                        continue; // more to send
                    }
                    long next = Math.max(1, nextIndex.getOrDefault(peer, 1L));
                    long ni = Math.max(1, rep.conflictIndex());
                    if (ni >= next) {
                        ni = Math.max(1, next - 1); // always make progress backwards
                    }
                    nextIndex.put(peer, ni);
                }
            }
        } finally {
            synchronized (mu) {
                replicating.put(peer, false);
            }
        }
    }

    /** Commits the highest index stored on a majority, but only if it belongs to the current term. */
    private void advanceCommit() {
        List<Long> idxs = new ArrayList<>();
        for (String p : cfg.peers) {
            idxs.add(p.equals(cfg.id) ? lastIndex() : matchIndex.getOrDefault(p, 0L));
        }
        idxs.sort((x, y) -> Long.compare(y, x));
        long candidate = idxs.get(majority() - 1);
        if (candidate > commitIndex && log.get((int) candidate).term() == term) {
            commitIndex = candidate;
            kickApply();
        }
    }

    // ------------------------------------------------------------------ replication (follower side)

    /** AppendEntries RPC handler. */
    public AppendEntriesReply handleAppendEntries(AppendEntriesArgs a) {
        synchronized (mu) {
            if (stopped || a.term() < term) {
                return new AppendEntriesReply(term, false, 0);
            }
            if (a.term() > term || role != Role.FOLLOWER) {
                becomeFollower(a.term());
            }
            leader = a.leaderId();
            resetElectionTimer();

            long last = lastIndex();
            if (a.prevLogIndex() > last) {
                return new AppendEntriesReply(term, false, last + 1);
            }
            if (log.get((int) a.prevLogIndex()).term() != a.prevLogTerm()) {
                long bad = log.get((int) a.prevLogIndex()).term();
                long i = a.prevLogIndex();
                while (i > 1 && log.get((int) (i - 1)).term() == bad) {
                    i--; // skip the whole conflicting term in one round trip
                }
                return new AppendEntriesReply(term, false, i);
            }
            boolean changed = false;
            for (int k = 0; k < a.entries().size(); k++) {
                Entry e = a.entries().get(k);
                long idx = a.prevLogIndex() + 1 + k;
                if (idx <= lastIndex()) {
                    if (log.get((int) idx).term() == e.term()) {
                        continue; // already have it
                    }
                    log.subList((int) idx, log.size()).clear(); // conflict: drop this entry and everything after
                }
                log.add(e);
                changed = true;
            }
            if (changed) {
                persist();
            }
            if (a.leaderCommit() > commitIndex) {
                long newCommit = Math.min(a.leaderCommit(), a.prevLogIndex() + a.entries().size());
                if (newCommit > commitIndex) {
                    commitIndex = newCommit;
                    kickApply();
                }
            }
            return new AppendEntriesReply(term, true, 0);
        }
    }

    // ------------------------------------------------------------------ apply loop

    private void kickApply() {
        if (applyKick.availablePermits() == 0) {
            applyKick.release();
        }
    }

    private void applyLoop() {
        while (true) {
            try {
                applyKick.tryAcquire(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            while (true) {
                final Entry e;
                synchronized (mu) {
                    if (stopped && lastApplied >= commitIndex) {
                        return;
                    }
                    if (lastApplied >= commitIndex) {
                        break;
                    }
                    lastApplied++;
                    e = log.get((int) lastApplied);
                }
                if (e.cmd() != null) {
                    cfg.fsm.apply(e.index(), e.cmd());
                }
                synchronized (mu) {
                    Waiter w = waiters.remove(e.index());
                    if (w != null) {
                        if (w.term() == e.term()) {
                            w.done().complete(null);
                        } else {
                            w.done().completeExceptionally(new RaftException.LeadershipLost()); // overwritten
                        }
                    }
                }
            }
            synchronized (mu) {
                if (stopped) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ client API

    /**
     * Appends {@code cmd} to the log and waits until it is committed and applied locally.
     *
     * @return the log index of the command
     * @throws RaftException.NotLeader if this node is not the leader
     * @throws RaftException.LeadershipLost if leadership changed first (the command may still commit later)
     * @throws RaftException.Timeout if it did not commit within {@code timeout} (outcome unknown)
     */
    public long propose(String cmd, Duration timeout) throws RaftException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        long idx;
        synchronized (mu) {
            if (stopped) {
                throw new RaftException.Stopped();
            }
            if (role != Role.LEADER) {
                throw new RaftException.NotLeader(leader);
            }
            idx = lastIndex() + 1;
            log.add(new Entry(term, idx, cmd));
            persist();
            matchIndex.put(cfg.id, idx);
            waiters.put(idx, new Waiter(term, done));
            advanceCommit();
            broadcast();
        }
        try {
            done.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return idx;
        } catch (TimeoutException e) {
            synchronized (mu) {
                waiters.remove(idx);
            }
            throw new RaftException.Timeout();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RaftException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (mu) {
                waiters.remove(idx);
            }
            throw new RaftException.Stopped();
        }
    }
}
