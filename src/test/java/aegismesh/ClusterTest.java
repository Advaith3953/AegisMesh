package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.app.GatewayNode;
import aegismesh.sim.Worker;
import aegismesh.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClusterTest {
    private TestCluster c;

    @AfterEach
    void tearDown() {
        if (c != null) {
            c.close();
        }
    }

    private static Map<String, Object> statusOf(String ctl) {
        return Json.obj(Json.parse(TestUtil.get(ctl + "/admin/status").body()));
    }

    private static long version(String ctl) {
        return Json.lng(Json.obj(statusOf(ctl).get("state")), "version", -1);
    }

    @Test
    void electsALeaderAndEveryNodeReportsIt() throws Exception {
        c = new TestCluster("adaptive");
        String leader = c.leaderId();
        for (String id : c.alive()) {
            TestUtil.eventually(id + " knows the leader", Duration.ofSeconds(10), () -> {
                Map<String, Object> raft = Json.obj(statusOf(c.ctl(id)).get("raft"));
                return leader.equals(raft.get("leader"));
            });
            Map<String, Object> raft = Json.obj(statusOf(c.ctl(id)).get("raft"));
            assertEquals(id, raft.get("id"));
            assertEquals(id.equals(leader) ? "leader" : "follower", raft.get("role"));
            assertTrue(Json.lng(raft, "term", 0) >= 1);
        }
        assertTrue(Files.exists(c.dir.resolve(leader + ".raft.json")), "Raft state is durable on disk");
    }

    @Test
    void configurationWrittenThroughAnyNodeReachesEveryRouter() throws Exception {
        c = new TestCluster("adaptive");
        String follower = c.follower();
        Worker w1 = c.newWorker("w1");
        Worker w2 = c.newWorker("w2");
        c.register(follower, w1); // forwarded to the leader
        c.register(c.leaderId(), w2);
        c.awaitBackends(2);
        assertEquals(200, TestUtil.post(c.ctl(follower) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"roundrobin\"}").status());
        for (String id : c.alive()) {
            TestUtil.eventually(id + " applies the policy", Duration.ofSeconds(10),
                    () -> "roundrobin".equals(c.nodes.get(id).router().services().get("api")));
        }
        // every node holds the same replicated registry
        String first = Json.write(Json.obj(statusOf(c.ctl("gw1")).get("state")).get("backends"));
        for (String id : c.alive()) {
            assertEquals(first, Json.write(Json.obj(statusOf(c.ctl(id)).get("state")).get("backends")));
        }
    }

    @Test
    void invalidAdminRequestsAreRefusedAndNeverTouchTheLog() throws Exception {
        c = new TestCluster("adaptive");
        String leader = c.leaderId();
        String follower = c.follower();
        long before = version(c.ctl(leader));
        String[][] bad = {
                {"/admin/policy", "{\"service\":\"api\",\"policy\":\"bogus\"}"},
                {"/admin/policy", "{\"policy\":\"adaptive\"}"},
                {"/admin/backends", "{\"id\":\"w9\",\"service\":\"api\"}"},
                {"/admin/backends", "{\"id\":\"w9\",\"service\":\"api\",\"addr\":\"localhost\"}"},
                {"/admin/backends", "{\"id\":\"w9\",\"service\":\"api\",\"addr\":\"h:1\",\"weight\":-2}"},
                {"/admin/backends", "{\"id\":\"w9\",\"service\":\"a/b\",\"addr\":\"h:1\"}"},
                {"/admin/command", "{\"op\":\"does_not_exist\"}"},
                {"/admin/backends", "{nope"},
                {"/admin/backends", "{\"weight\":\"heavy\"}"},
                {"/admin/command", "[]"}};
        for (String node : new String[] {leader, follower}) {
            for (String[] b : bad) {
                TestUtil.Resp r = TestUtil.post(c.ctl(node) + b[0], b[1]);
                assertEquals(400, r.status(), node + " " + b[0] + " " + b[1] + " -> " + r.body());
            }
        }
        assertEquals(before, version(c.ctl(leader)), "invalid commands must not change replicated state");
        assertEquals(400, TestUtil.post(c.ctl(follower) + "/admin/backends/remove", "").status());
        for (String path : new String[] {"/admin/backends", "/admin/backends/remove", "/admin/policy", "/admin/command",
                "/raft/vote", "/raft/append"}) {
            TestUtil.Resp r = TestUtil.get(c.ctl(leader) + path);
            assertEquals(405, r.status(), path);
            assertEquals("POST", r.header("Allow"), path);
        }
        assertEquals(404, TestUtil.get(c.ctl(leader) + "/nope").status());
        assertEquals(400, TestUtil.post(c.ctl(leader) + "/raft/vote", "garbage").status());
        assertEquals(200, TestUtil.post(c.ctl(follower) + "/admin/backends/remove?id=never-existed", "").status());
    }

    @Test
    void readinessAndLivenessFollowTheClustersRealCondition() throws Exception {
        c = new TestCluster("adaptive");
        String gw = c.follower();
        c.leaderId();
        TestUtil.eventually("leader known", Duration.ofSeconds(10), () -> !c.nodes.get(gw).node().status().leader().isEmpty());
        TestUtil.Resp none = TestUtil.get(c.data(gw) + "/readyz");
        assertEquals(503, none.status());
        assertTrue(none.body().contains("no backends registered"), none.body());
        assertEquals(200, TestUtil.get(c.data(gw) + "/healthz").status());
        assertEquals(503, TestUtil.get(c.data(gw) + "/api/work").status());

        Worker w = c.newWorker("w1");
        c.register(gw, w);
        TestUtil.eventually("ready", Duration.ofSeconds(10), () -> TestUtil.get(c.data(gw) + "/readyz").status() == 200);
        assertEquals(200, TestUtil.get(c.data(gw) + "/api/work").status());

        w.kill();
        TestUtil.eventually("readiness drops with the only backend", Duration.ofSeconds(10), () -> {
            TestUtil.Resp r = TestUtil.get(c.data(gw) + "/readyz");
            return r.status() == 503 && r.body().contains("no healthy backend");
        });
        assertEquals(200, TestUtil.get(c.data(gw) + "/healthz").status(), "liveness must not depend on backends");
        w.restart();
        TestUtil.eventually("recovery", Duration.ofSeconds(10), () -> TestUtil.get(c.data(gw) + "/readyz").status() == 200);
    }

    @Test
    void trafficIsRoutedThroughEveryGatewayAndBalancedUnderRoundRobin() throws Exception {
        c = new TestCluster("adaptive");
        String gw = c.follower();
        for (int i = 1; i <= 3; i++) {
            c.register(gw, c.newWorker("w" + i));
        }
        assertEquals(200, TestUtil.post(c.ctl(gw) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"roundrobin\"}").status());
        for (String id : c.alive()) {
            TestUtil.eventually(id + " converged", Duration.ofSeconds(10), () ->
                    c.nodes.get(id).router().all().size() == 3 && "roundrobin".equals(c.nodes.get(id).router().services().get("api")));
        }
        for (String id : c.alive()) {
            Map<String, Integer> counts = new TreeMap<>();
            for (int i = 0; i < 30; i++) {
                TestUtil.Resp r = TestUtil.get(c.data(id) + "/api/work");
                assertEquals(200, r.status());
                counts.merge(r.header("X-Aegis-Backend"), 1, Integer::sum);
            }
            assertEquals(Map.of("w1", 10, "w2", 10, "w3", 10), counts, id);
        }
    }

    @Test
    void removingABackendDrainsItEverywhere() throws Exception {
        c = new TestCluster("roundrobin");
        String gw = c.follower();
        Worker a = c.newWorker("w1");
        Worker b = c.newWorker("w2");
        c.register(gw, a);
        c.register(gw, b);
        c.awaitBackends(2);
        assertEquals(200, TestUtil.post(c.ctl(gw) + "/admin/backends/remove?id=w2", "").status());
        c.awaitBackends(1);
        for (String id : c.alive()) {
            for (int i = 0; i < 10; i++) {
                assertEquals("w1", TestUtil.get(c.data(id) + "/api/work").header("X-Aegis-Backend"));
            }
        }
    }

    @Test
    void theClusterSurvivesALeaderCrashAndARestartedNodeCatchesUp() throws Exception {
        c = new TestCluster("adaptive");
        String leader = c.leaderId();
        Worker w1 = c.newWorker("w1");
        c.register(leader, w1);
        c.awaitBackends(1);

        c.kill(leader);
        String newLeader = c.leaderId();
        assertNotEquals(leader, newLeader);
        for (String id : c.alive()) {
            TestUtil.eventually(id + " still routes", Duration.ofSeconds(10), () -> TestUtil.get(c.data(id) + "/api/work").status() == 200);
        }
        Worker w2 = c.newWorker("w2"); // the control plane is writable again through a survivor
        c.register(c.alive().get(0), w2);
        c.awaitBackends(2);

        c.start(leader); // same data dir and ports
        TestUtil.eventually("restarted node catches up", Duration.ofSeconds(20),
                () -> c.nodes.get(leader).router().all().size() == 2);
        assertEquals(2, ((Map<?, ?>) Json.obj(statusOf(c.ctl(leader)).get("state")).get("backends")).size());
    }

    @Test
    void withoutAQuorumWritesAreRefusedWith503() throws Exception {
        c = new TestCluster("adaptive");
        String leader = c.leaderId();
        for (String id : c.alive()) {
            if (!id.equals(leader)) {
                c.kill(id);
            }
        }
        TestUtil.Resp r = TestUtil.post(c.ctl(leader) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"roundrobin\"}");
        assertEquals(503, r.status(), r.body());
        assertEquals(200, TestUtil.get(c.data(leader) + "/healthz").status(), "the process stays alive and answers probes");
    }

    @Test
    void aWriteToAFollowerDuringALeaderlessMomentFailsCleanlyThenSucceedsWithRetry() throws Exception {
        c = new TestCluster("adaptive");
        String leader = c.leaderId();
        String follower = c.alive().stream().filter(x -> !x.equals(leader)).findFirst().orElseThrow();
        c.kill(leader);
        // either an immediate clean error or success once a new leader exists: never a hang or a 5xx other than 503
        TestUtil.Resp first = TestUtil.post(c.ctl(follower) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"roundrobin\"}");
        assertTrue(first.status() == 200 || first.status() == 503, first.status() + " " + first.body());
        TestUtil.eventually("write succeeds after failover", Duration.ofSeconds(20), () ->
                TestUtil.post(c.ctl(follower) + "/admin/policy", "{\"service\":\"api\",\"policy\":\"adaptive\"}").status() == 200);
    }

    // ---------------------------------------------------------------- start-up validation

    private GatewayNode.Config single(Path dir) {
        GatewayNode.Config cfg = new GatewayNode.Config();
        cfg.id = "gw1";
        cfg.peers = Map.of("gw1", "127.0.0.1:" + TestUtil.freePort());
        cfg.dataAddr = "127.0.0.1:" + TestUtil.freePort();
        cfg.dataDir = dir;
        cfg.telemetry = "none";
        return cfg;
    }

    @Test
    void refusesToStartOnBadConfiguration(@org.junit.jupiter.api.io.TempDir Path dir) {
        GatewayNode.Config notListed = single(dir);
        notListed.id = "zz";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new GatewayNode(notListed).start())
                .getMessage().contains("must be listed in -peers"));
        GatewayNode.Config badPolicy = single(dir);
        badPolicy.policy = "random";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new GatewayNode(badPolicy).start())
                .getMessage().contains("unknown -policy"));
        GatewayNode.Config badTel = single(dir);
        badTel.telemetry = "magic";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new GatewayNode(badTel).start())
                .getMessage().contains("unknown -telemetry"));
        GatewayNode.Config badAddr = single(dir);
        badAddr.dataAddr = "not-an-address";
        assertThrows(IllegalArgumentException.class, () -> new GatewayNode(badAddr).start());
    }

    @Test
    void refusesToStartOnACorruptRaftStateFileInsteadOfForgettingItsVote(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("gw1.raft.json"), "{broken");
        IOException e = assertThrows(IOException.class, () -> new GatewayNode(single(dir)).start());
        assertTrue(e.getMessage().contains("corrupt"), e.getMessage());
    }

    @Test
    void aSingleNodeDeploymentWorksEndToEnd(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        GatewayNode.Config cfg = single(dir);
        GatewayNode n = new GatewayNode(cfg);
        n.start();
        Worker w = new Worker("w1", new Worker.Config().withServiceMs(1));
        w.start("127.0.0.1:0");
        try {
            String ctl = "http://" + cfg.peers.get("gw1");
            TestUtil.eventually("leader", Duration.ofSeconds(10), () -> !n.node().status().leader().isEmpty());
            assertEquals(200, TestUtil.post(ctl + "/admin/backends", "{\"id\":\"w1\",\"service\":\"api\",\"addr\":\"" + w.addr() + "\"}").status());
            TestUtil.eventually("served", Duration.ofSeconds(10), () -> TestUtil.get("http://" + cfg.dataAddr + "/api/x").status() == 200);
            assertFalse(n.id().isEmpty());
            assertEquals(n.dataPort(), Integer.parseInt(cfg.dataAddr.substring(cfg.dataAddr.lastIndexOf(':') + 1)));
        } finally {
            n.stop();
            w.kill();
        }
        // restart on the same directory: the replicated config is rebuilt from the durable log
        GatewayNode again = new GatewayNode(cfg);
        again.start();
        try {
            TestUtil.eventually("config recovered from disk", Duration.ofSeconds(15), () -> again.router().all().size() == 1);
        } finally {
            again.stop();
        }
        assertEquals(List.of("gw1"), List.copyOf(cfg.peers.keySet()));
    }
}
