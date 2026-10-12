package aegismesh.app;

import aegismesh.breaker.Breaker;
import aegismesh.control.ControlServer;
import aegismesh.control.Fsm;
import aegismesh.control.Plane;
import aegismesh.lb.DataPlane;
import aegismesh.lb.EventLog;
import aegismesh.lb.Gateway;
import aegismesh.lb.HealthChecker;
import aegismesh.lb.Router;
import aegismesh.lb.ScoreConfig;
import aegismesh.raft.FileStorage;
import aegismesh.raft.HttpTransport;
import aegismesh.raft.Node;
import aegismesh.telemetry.Feeder;
import aegismesh.telemetry.ProcSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One complete gateway process: router + proxy (data plane), Raft node + admin API (control plane), health checker
 * and optional kernel telemetry. Used by the {@code gateway} command, the benchmark and the integration tests.
 */
public final class GatewayNode {
    /** Settings of one gateway node. {@code peers} maps every node id (including this one) to its control address. */
    public static final class Config {
        public String id = "";
        public String dataAddr = "127.0.0.1:8080";
        public Map<String, String> peers = new LinkedHashMap<>();
        public Path dataDir = Path.of("aegis-data");
        public String policy = "adaptive";
        public boolean resilient = true;
        public String telemetry = "proc"; // none | proc
        public Duration healthInterval = Duration.ofMillis(500);
        public Duration heartbeat = Duration.ofMillis(50);
        public Duration electionMin = Duration.ofMillis(250);
        public Duration electionMax = Duration.ofMillis(500);
    }

    private final Config cfg;
    private Router router;
    private Node node;
    private Plane plane;
    private ControlServer control;
    private DataPlane data;
    private HealthChecker health;
    private Feeder feeder;

    public GatewayNode(Config cfg) {
        this.cfg = cfg;
    }

    /** @throws IllegalArgumentException for an invalid configuration; @throws IOException if it cannot start */
    public void start() throws IOException {
        if (!cfg.peers.containsKey(cfg.id)) {
            throw new IllegalArgumentException("-id \"" + cfg.id + "\" must be listed in -peers");
        }
        if (!cfg.policy.equals("adaptive") && !cfg.policy.equals("roundrobin")) {
            throw new IllegalArgumentException("unknown -policy \"" + cfg.policy + "\" (want adaptive or roundrobin)");
        }
        if (!cfg.telemetry.equals("none") && !cfg.telemetry.equals("proc")) {
            throw new IllegalArgumentException("unknown -telemetry \"" + cfg.telemetry + "\" (want none or proc)");
        }
        router = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), cfg.policy);
        Fsm fsm = new Fsm(state -> {
            router.setPolicies(state.policies());
            router.reconcile(state.specs());
        });
        Node.Config nc = new Node.Config(cfg.id, new ArrayList<>(cfg.peers.keySet()),
                new HttpTransport(cfg.peers), fsm);
        nc.storage = new FileStorage(cfg.dataDir.resolve(cfg.id + ".raft.json"));
        nc.heartbeat = cfg.heartbeat;
        nc.electionMin = cfg.electionMin;
        nc.electionMax = cfg.electionMax;
        node = new Node(nc); // throws on corrupt state: refuse to start rather than forget a vote
        plane = new Plane(node, fsm, cfg.peers);

        control = new ControlServer(cfg.peers.get(cfg.id), plane);
        data = new DataPlane(cfg.dataAddr, new Gateway(router, cfg.resilient), () -> plane.readiness(router));
        control.start();
        data.start();
        node.start();
        if (cfg.resilient) {
            health = new HealthChecker(router, cfg.healthInterval);
            health.start();
        }
        if (cfg.telemetry.equals("proc")) {
            feeder = new Feeder(new ProcSource(), router, Duration.ofMillis(200));
            feeder.start();
        }
    }

    public void stop() {
        if (feeder != null) {
            feeder.stop();
        }
        if (health != null) {
            health.stop();
        }
        if (data != null) {
            data.stop();
        }
        if (control != null) {
            control.stop();
        }
        if (node != null) {
            node.stop();
        }
    }

    public Router router() {
        return router;
    }

    public Plane plane() {
        return plane;
    }

    public Node node() {
        return node;
    }

    public int dataPort() {
        return data.port();
    }

    public String id() {
        return cfg.id;
    }
}
