package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.raft.MemNetwork;
import aegismesh.raft.MemStorage;
import aegismesh.raft.Node;
import aegismesh.raft.RaftException;
import aegismesh.raft.Role;
import aegismesh.raft.Types.AppendEntriesArgs;
import aegismesh.raft.Types.AppendEntriesReply;
import aegismesh.raft.Types.Entry;
import aegismesh.raft.Types.PersistentState;
import aegismesh.raft.Types.RequestVoteArgs;
import aegismesh.raft.Types.Storage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RaftTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private final List<Node> started = new ArrayList<>();

    /** A cluster over an in-memory network, each node recording what its state machine applied. */
    private final class Cluster {
        final MemNetwork net = new MemNetwork();
        final List<String> ids = new ArrayList<>();
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, MemStorage> stores = new LinkedHashMap<>();
        final Map<String, List<String>> applied = new LinkedHashMap<>();

        Cluster(int n) throws IOException {
            for (int i = 1; i <= n; i++) {
                ids.add("n" + i);
            }
            for (String id : ids) {
                stores.put(id, new MemStorage());
                startNode(id);
            }
        }

        void startNode(String id) throws IOException {
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            applied.put(id, log);
            Node.Config c = new Node.Config(id, ids, net.transportFor(id), (idx, cmd) -> log.add(cmd));
            c.storage = stores.get(id);
            c.heartbeat = Duration.ofMillis(25);
            c.electionMin = Duration.ofMillis(150);
            c.electionMax = Duration.ofMillis(300);
            Node n = new Node(c);
            nodes.put(id, n);
            net.register(id, n);
            net.setDown(id, false);
            n.start();
            started.add(n);
        }

        void crash(String id) {
            net.setDown(id, true);
            nodes.get(id).stop();
        }

        Node leader() {
            Node[] found = new Node[1];
            TestUtil.eventually("a leader", WAIT, () -> {
                List<Node> ls = new ArrayList<>();
                for (Node n : nodes.values()) {
                    if (n.status().role() == Role.LEADER && !net.toString().isEmpty()) {
                        ls.add(n);
                    }
                }
                if (ls.size() == 1) {
                    found[0] = ls.get(0);
                    return true;
                }
                return false;
            });
            return found[0];
        }

        Node leaderAmong(List<String> members) {
            Node[] found = new Node[1];
            TestUtil.eventually("a leader among " + members, WAIT, () -> {
                for (String id : members) {
                    if (nodes.get(id).status().role() == Role.LEADER) {
                        found[0] = nodes.get(id);
                        return true;
                    }
                }
                return false;
            });
            return found[0];
        }

        void awaitApplied(List<String> members, List<String> expected) {
            TestUtil.eventually("applied " + expected, WAIT, () -> {
                for (String id : members) {
                    if (!applied.get(id).equals(expected)) {
                        return false;
                    }
                }
                return true;
            });
        }
    }

    @AfterEach
    void tearDown() {
        started.forEach(Node::stop);
    }

    // ---------------------------------------------------------------- elections and replication

    @Test
    void electsExactlyOneLeaderAndKeepsItWhileHealthy() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        long term = l.status().term();
        Thread.sleep(800);
        assertEquals(Role.LEADER, l.status().role());
        assertEquals(term, l.status().term(), "a stable cluster must not keep holding elections");
        long leaders = c.nodes.values().stream().filter(n -> n.status().role() == Role.LEADER).count();
        assertEquals(1, leaders);
        for (Node n : c.nodes.values()) {
            TestUtil.eventually("followers learn the leader", WAIT, () -> n.status().leader().equals(l.id()));
        }
    }

    @Test
    void aSingleNodeClusterElectsItselfAndCommits() throws Exception {
        Cluster c = new Cluster(1);
        Node l = c.leader();
        l.propose("solo", Duration.ofSeconds(5));
        assertEquals(List.of("solo"), c.applied.get("n1"));
    }

    @Test
    void replicatesCommandsInOrderToEveryNode() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        List<String> cmds = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            cmds.add("cmd-" + i);
            l.propose("cmd-" + i, Duration.ofSeconds(5));
        }
        c.awaitApplied(c.ids, cmds);
        long idx = l.status().commitIndex();
        assertTrue(idx >= 31, "30 commands plus the leader's no-op");
    }

    @Test
    void aFollowerRefusesProposalsAndNamesTheLeader() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        Node follower = c.nodes.values().stream().filter(n -> n != l).findFirst().orElseThrow();
        TestUtil.eventually("follower knows leader", WAIT, () -> follower.status().leader().equals(l.id()));
        RaftException.NotLeader e = assertThrows(RaftException.NotLeader.class, () -> follower.propose("x", Duration.ofSeconds(1)));
        assertEquals(l.id(), e.leader());
    }

    @Test
    void concurrentProposalsAreAllCommittedExactlyOnce() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            ts.add(Thread.startVirtualThread(() -> {
                for (int i = 0; i < 10; i++) {
                    try {
                        l.propose("t" + id + "-" + i, Duration.ofSeconds(10));
                    } catch (RaftException e) {
                        throw new AssertionError(e);
                    }
                }
            }));
        }
        for (Thread t : ts) {
            t.join();
        }
        TestUtil.eventually("all 80 applied everywhere", WAIT, () -> c.applied.values().stream().allMatch(a -> a.size() == 80));
        assertEquals(80, new java.util.HashSet<>(c.applied.get("n1")).size());
        assertEquals(c.applied.get("n1"), c.applied.get("n2"));
        assertEquals(c.applied.get("n1"), c.applied.get("n3"));
    }

    // ---------------------------------------------------------------- failures

    @Test
    void survivesTheLeaderCrashingAndKeepsEverythingCommitted() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        l.propose("before", Duration.ofSeconds(5));
        c.awaitApplied(c.ids, List.of("before"));
        c.crash(l.id());
        List<String> survivors = new ArrayList<>(c.ids);
        survivors.remove(l.id());
        Node nl = c.leaderAmong(survivors);
        assertNotEquals(l.id(), nl.id());
        nl.propose("after", Duration.ofSeconds(5));
        c.awaitApplied(survivors, List.of("before", "after"));
    }

    @Test
    void withoutAMajorityNothingCommits() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        for (String id : c.ids) {
            if (!id.equals(l.id())) {
                c.crash(id);
            }
        }
        assertThrows(RaftException.Timeout.class, () -> l.propose("lonely", Duration.ofMillis(500)));
        assertTrue(c.applied.get(l.id()).isEmpty(), "an entry on a minority must never be applied");
    }

    @Test
    void anIsolatedLeaderCannotCommitAndItsEntryIsOverwrittenAfterHealing() throws Exception {
        Cluster c = new Cluster(3);
        Node old = c.leader();
        old.propose("committed", Duration.ofSeconds(5));
        c.awaitApplied(c.ids, List.of("committed"));

        c.net.isolate(old.id(), c.ids, true); // partition: old leader alone
        assertThrows(RaftException.class, () -> old.propose("doomed", Duration.ofMillis(600)));

        List<String> majority = new ArrayList<>(c.ids);
        majority.remove(old.id());
        Node nl = c.leaderAmong(majority);
        nl.propose("winner", Duration.ofSeconds(5));

        c.net.isolate(old.id(), c.ids, false); // heal
        TestUtil.eventually("old leader steps down", WAIT, () -> old.status().role() != Role.LEADER);
        c.awaitApplied(c.ids, List.of("committed", "winner"));
        assertFalse(c.applied.get(old.id()).contains("doomed"), "the uncommitted entry must be discarded");
    }

    @Test
    void aProposalOnALeaderThatLosesLeadershipFailsInsteadOfHanging() throws Exception {
        Cluster c = new Cluster(3);
        Node old = c.leader();
        c.net.isolate(old.id(), c.ids, true);
        Thread t = Thread.startVirtualThread(() -> {
            try {
                old.propose("x", Duration.ofSeconds(8));
            } catch (RaftException ignored) {
                // expected
            }
        });
        List<String> majority = new ArrayList<>(c.ids);
        majority.remove(old.id());
        c.leaderAmong(majority).propose("y", Duration.ofSeconds(5));
        c.net.isolate(old.id(), c.ids, false);
        t.join(10_000);
        assertFalse(t.isAlive(), "propose() must return once leadership is lost");
    }

    @Test
    void aWholeClusterRestartRecoversFromDiskState() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        List<String> cmds = List.of("a", "b", "c");
        for (String s : cmds) {
            l.propose(s, Duration.ofSeconds(5));
        }
        c.awaitApplied(c.ids, cmds);
        long term = l.status().term();
        for (String id : c.ids) {
            c.crash(id);
        }
        for (String id : c.ids) {
            c.startNode(id);
        }
        Node nl = c.leader();
        assertTrue(nl.status().term() > term, "a new election happens after a restart");
        nl.propose("d", Duration.ofSeconds(5));
        c.awaitApplied(c.ids, List.of("a", "b", "c", "d")); // restarted state machines replay the whole log
    }

    @Test
    void aRestartedNodeCatchesUpOnWhatItMissed() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        String victim = c.ids.stream().filter(id -> !id.equals(l.id())).findFirst().orElseThrow();
        l.propose("one", Duration.ofSeconds(5));
        c.crash(victim);
        for (int i = 0; i < 40; i++) {
            l.propose("m" + i, Duration.ofSeconds(5));
        }
        c.startNode(victim);
        List<String> expect = new ArrayList<>(List.of("one"));
        for (int i = 0; i < 40; i++) {
            expect.add("m" + i);
        }
        c.awaitApplied(List.of(victim), expect);
    }

    @Test
    void proposeAfterStopAndStopDuringProposeBothReportStopped() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        for (String id : c.ids) {
            if (!id.equals(l.id())) {
                c.crash(id);
            }
        }
        Thread t = Thread.startVirtualThread(() -> {
            try {
                l.propose("pending", Duration.ofSeconds(30));
                throw new AssertionError("must not commit");
            } catch (RaftException e) {
                assertTrue(e instanceof RaftException.Stopped, e.toString());
            }
        });
        Thread.sleep(300);
        l.stop();
        l.stop(); // idempotent
        t.join(5000);
        assertFalse(t.isAlive());
        assertThrows(RaftException.Stopped.class, () -> l.propose("later", Duration.ofSeconds(1)));
    }

    // ---------------------------------------------------------------- protocol rules (handlers called directly)

    private Node idle(Storage st) throws IOException {
        Node.Config c = new Node.Config("me", List.of("me", "a", "b"), new MemNetwork().transportFor("me"), (i, s) -> { });
        c.storage = st;
        return new Node(c); // never started: no timers, handlers only
    }

    @Test
    void aVoteIsGrantedOncePerTermAndSurvivesARestart() throws Exception {
        MemStorage st = new MemStorage();
        Node n = idle(st);
        assertTrue(n.handleRequestVote(new RequestVoteArgs(5, "a", 0, 0)).voteGranted());
        assertFalse(n.handleRequestVote(new RequestVoteArgs(5, "b", 0, 0)).voteGranted(), "second candidate, same term");
        assertTrue(n.handleRequestVote(new RequestVoteArgs(5, "a", 0, 0)).voteGranted(), "the same candidate may ask again");
        Node reborn = idle(st); // crash and restart with the same disk
        assertEquals(5, reborn.status().term());
        assertFalse(reborn.handleRequestVote(new RequestVoteArgs(5, "b", 0, 0)).voteGranted(), "the vote must not be forgotten");
    }

    @Test
    void staleTermsAreRejectedAndNewerTermsAreAdopted() throws Exception {
        Node n = idle(new MemStorage());
        n.handleRequestVote(new RequestVoteArgs(7, "a", 0, 0));
        assertFalse(n.handleRequestVote(new RequestVoteArgs(3, "b", 9, 9)).voteGranted());
        assertEquals(7, n.handleRequestVote(new RequestVoteArgs(3, "b", 9, 9)).term());
        AppendEntriesReply r = n.handleAppendEntries(new AppendEntriesArgs(2, "a", 0, 0, List.of(), 0));
        assertFalse(r.success());
        assertEquals(7, r.term());
        assertTrue(n.handleAppendEntries(new AppendEntriesArgs(9, "a", 0, 0, List.of(), 0)).success());
        assertEquals(9, n.status().term());
        assertEquals("a", n.status().leader());
    }

    @Test
    void votesOnlyForCandidatesWithAtLeastAsCompleteALog() throws Exception {
        Node n = idle(new MemStorage());
        n.handleAppendEntries(new AppendEntriesArgs(3, "L", 0, 0, List.of(new Entry(3, 1, "x"), new Entry(3, 2, "y")), 0));
        assertFalse(n.handleRequestVote(new RequestVoteArgs(4, "c1", 1, 3)).voteGranted(), "shorter log, same last term");
        assertFalse(n.handleRequestVote(new RequestVoteArgs(5, "c2", 9, 2)).voteGranted(), "older last term loses despite length");
        assertTrue(n.handleRequestVote(new RequestVoteArgs(6, "c3", 2, 3)).voteGranted());
    }

    @Test
    void appendEntriesEnforcesTheConsistencyCheckAndHintsWhereToRetry() throws Exception {
        Node n = idle(new MemStorage());
        AppendEntriesReply gap = n.handleAppendEntries(new AppendEntriesArgs(1, "L", 5, 1, List.of(), 0));
        assertFalse(gap.success());
        assertEquals(1, gap.conflictIndex(), "an empty log asks the leader to start from the beginning");
        n.handleAppendEntries(new AppendEntriesArgs(1, "L", 0, 0, List.of(new Entry(1, 1, "a"), new Entry(1, 2, "b"),
                new Entry(2, 3, "c")), 0));
        AppendEntriesReply mismatch = n.handleAppendEntries(new AppendEntriesArgs(3, "L", 3, 9, List.of(), 0));
        assertFalse(mismatch.success());
        assertEquals(3, mismatch.conflictIndex(), "skip back to the first entry of the conflicting term");
    }

    @Test
    void conflictingSuffixIsReplacedDuplicatesAreIgnoredAndCommitIsBounded() throws Exception {
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        Node.Config c = new Node.Config("me", List.of("me", "a", "b"), new MemNetwork().transportFor("me"), (i, s) -> applied.add(s));
        c.storage = new MemStorage();
        Node n = new Node(c);
        n.start();
        try {
            n.handleAppendEntries(new AppendEntriesArgs(1, "L", 0, 0, List.of(new Entry(1, 1, "a"), new Entry(1, 2, "stale1"),
                    new Entry(1, 3, "stale2")), 0));
            // a new leader in term 2 overwrites everything after index 1
            AppendEntriesReply r = n.handleAppendEntries(new AppendEntriesArgs(2, "L2", 1, 1,
                    List.of(new Entry(2, 2, "fresh")), 100));
            assertTrue(r.success());
            assertEquals(2, n.status().lastIndex(), "the conflicting suffix must be truncated");
            assertEquals(2, n.status().commitIndex(), "commit is bounded by the entries this RPC actually covers");
            n.handleAppendEntries(new AppendEntriesArgs(2, "L2", 1, 1, List.of(new Entry(2, 2, "fresh")), 2)); // duplicate
            TestUtil.eventually("applied", WAIT, () -> applied.size() == 2);
            assertEquals(List.of("a", "fresh"), applied);
        } finally {
            n.stop();
        }
    }

    @Test
    void aStorageFailureStopsTheNodeInsteadOfAcknowledgingUndurableState() throws Exception {
        boolean[] broken = {false};
        Storage flaky = new Storage() {
            @Override
            public PersistentState load() {
                return PersistentState.empty();
            }

            @Override
            public void save(PersistentState s) throws IOException {
                if (broken[0]) {
                    throw new IOException("disk full");
                }
            }
        };
        Node n = idle(flaky);
        assertTrue(n.handleRequestVote(new RequestVoteArgs(1, "a", 0, 0)).voteGranted());
        assertNull(n.failure());
        broken[0] = true;
        assertThrows(UncheckedIOException.class, () -> n.handleRequestVote(new RequestVoteArgs(2, "b", 0, 0)));
        assertNotNull(n.failure());
        assertTrue(n.failure().contains("disk full"));
        assertFalse(n.handleRequestVote(new RequestVoteArgs(3, "c", 0, 0)).voteGranted(), "a failed node grants nothing");
        assertThrows(RaftException.Stopped.class, () -> n.propose("x", Duration.ofSeconds(1)));
    }

    @Test
    void aCorruptLogOnDiskIsRefused() {
        MemStorage st = new MemStorage();
        try {
            st.save(new PersistentState(3, "", List.of(new Entry(1, 1, "a"), new Entry(1, 5, "gap"))));
        } catch (RuntimeException e) {
            throw new AssertionError(e);
        }
        IOException e = assertThrows(IOException.class, () -> idle(st));
        assertTrue(e.getMessage().contains("out of sequence"));
    }

    @Test
    void statusReflectsProgress() throws Exception {
        Cluster c = new Cluster(3);
        Node l = c.leader();
        l.propose("x", Duration.ofSeconds(5));
        var st = l.status();
        assertEquals(l.id(), st.id());
        assertEquals(Role.LEADER, st.role());
        assertEquals(l.id(), st.leader());
        assertTrue(st.lastApplied() >= 2 && st.lastIndex() >= st.commitIndex());
        assertEquals("leader", st.role().toString());
    }
}
