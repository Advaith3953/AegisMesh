package aegismesh;

import aegismesh.app.GatewayNode;
import aegismesh.raft.Role;
import aegismesh.sim.Worker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Three real gateway nodes (real HTTP, real files) inside the test JVM, plus helpers to drive them. */
final class TestCluster implements AutoCloseable {
    final Path dir;
    final Map<String, String> peers = new LinkedHashMap<>(); // id -> control addr
    final Map<String, String> dataAddrs = new LinkedHashMap<>();
    final Map<String, GatewayNode.Config> configs = new LinkedHashMap<>();
    final Map<String, GatewayNode> nodes = new LinkedHashMap<>();
    final List<Worker> workers = new ArrayList<>();

    TestCluster(String policy) throws IOException {
        dir = Files.createTempDirectory("aegis-test");
        for (int i = 1; i <= 3; i++) {
            peers.put("gw" + i, "127.0.0.1:" + TestUtil.freePort());
            dataAddrs.put("gw" + i, "127.0.0.1:" + TestUtil.freePort());
        }
        for (String id : peers.keySet()) {
            GatewayNode.Config c = new GatewayNode.Config();
            c.id = id;
            c.dataAddr = dataAddrs.get(id);
            c.peers = peers;
            c.dataDir = dir;
            c.policy = policy;
            c.telemetry = "none";
            c.healthInterval = Duration.ofMillis(100);
            c.heartbeat = Duration.ofMillis(25);
            c.electionMin = Duration.ofMillis(150);
            c.electionMax = Duration.ofMillis(300);
            configs.put(id, c);
            start(id);
        }
    }

    void start(String id) throws IOException {
        GatewayNode n = new GatewayNode(configs.get(id));
        n.start();
        nodes.put(id, n);
    }

    void kill(String id) {
        nodes.get(id).stop();
        nodes.remove(id);
    }

    String ctl(String id) {
        return "http://" + peers.get(id);
    }

    String data(String id) {
        return "http://" + dataAddrs.get(id);
    }

    List<String> alive() {
        return new ArrayList<>(nodes.keySet());
    }

    String leaderId() {
        String[] found = new String[1];
        TestUtil.eventually("a leader", Duration.ofSeconds(15), () -> {
            for (Map.Entry<String, GatewayNode> e : nodes.entrySet()) {
                if (e.getValue().node().status().role() == Role.LEADER) {
                    found[0] = e.getKey();
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    String follower() {
        String l = leaderId();
        return alive().stream().filter(x -> !x.equals(l)).findFirst().orElseThrow();
    }

    Worker newWorker(String id) throws IOException {
        Worker w = new Worker(id, new Worker.Config().withServiceMs(1));
        w.start("127.0.0.1:0");
        workers.add(w);
        return w;
    }

    /** Registers a worker through {@code via}'s admin API and waits for the write to be acknowledged. */
    void register(String via, Worker w) {
        TestUtil.eventually("register " + w.id, Duration.ofSeconds(15), () -> TestUtil.post(ctl(via) + "/admin/backends",
                "{\"id\":\"" + w.id + "\",\"service\":\"api\",\"addr\":\"" + w.addr() + "\"}").status() == 200);
    }

    void awaitBackends(int n) {
        for (String id : alive()) {
            TestUtil.eventually(id + " sees " + n + " backends", Duration.ofSeconds(15),
                    () -> nodes.get(id).router().all().size() == n);
        }
    }

    @Override
    public void close() {
        nodes.values().forEach(GatewayNode::stop);
        workers.forEach(Worker::kill);
        try (var walk = Files.walk(dir)) {
            walk.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
