package aegismesh.app;

import aegismesh.bench.Bench;
import aegismesh.sim.Worker;
import aegismesh.util.Cli;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Entry point: {@code java -jar aegismesh.jar <gateway|backend|bench> [flags]}. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
            System.out.println("usage: aegismesh <gateway|backend|bench> [flags]   (add -h after a command for its flags)");
            System.exit(args.length == 0 ? 2 : 0);
        }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "gateway" -> gateway(rest);
            case "backend" -> backend(rest);
            case "bench" -> Bench.main(rest);
            default -> {
                System.err.println("unknown command '" + args[0] + "' (want gateway, backend or bench)");
                System.exit(2);
            }
        }
    }

    /** Parses flags; prints usage and exits with the conventional codes on error (2) or help (0). */
    static Cli parse(Cli cli, String[] args) {
        try {
            return cli.parse(args);
        } catch (Cli.CliException e) {
            if (e.helpRequested()) {
                System.out.print(e.getMessage());
                System.exit(0);
            }
            System.err.println("error: " + e.getMessage());
            System.err.print(cli.usage());
            System.exit(2);
            return cli;
        }
    }

    /** Parses {@code gw1=127.0.0.1:9101,gw2=...}. */
    static Map<String, String> parsePeers(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s.isEmpty()) {
            return m;
        }
        for (String part : s.split(",")) {
            int i = part.indexOf('=');
            if (i <= 0 || i == part.length() - 1) {
                throw new IllegalArgumentException("bad -peers entry \"" + part + "\" (want id=host:port)");
            }
            m.put(part.substring(0, i), part.substring(i + 1));
        }
        return m;
    }

    private static void awaitShutdown(String what, Runnable cleanup) throws InterruptedException {
        // Run cleanup on SIGTERM/SIGINT, then exit 0 (a JVM would otherwise exit 143 after a signal).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(what + ": shutting down");
            System.out.flush();
            try {
                cleanup.run();
            } finally {
                Runtime.getRuntime().halt(0);
            }
        }));
        new CountDownLatch(1).await();
    }

    private static void gateway(String[] args) throws Exception {
        Cli cli = parse(new Cli("aegismesh gateway")
                .string("id", "", "this node's id; must appear in -peers")
                .string("listen", "127.0.0.1:8080", "data-plane address clients talk to")
                .string("peers", "", "comma-separated id=host:port control addresses of ALL nodes")
                .string("data", "aegis-data", "directory for durable Raft state")
                .string("policy", "adaptive", "default routing policy: adaptive or roundrobin")
                .bool("resilient", true, "health checks, circuit breakers and retries")
                .string("telemetry", "proc", "kernel telemetry source: none or proc")
                .duration("health-interval", "500ms", "active health check interval"), args);
        GatewayNode.Config c = new GatewayNode.Config();
        try {
            c.id = cli.getString("id");
            c.dataAddr = cli.getString("listen");
            c.peers = parsePeers(cli.getString("peers"));
            c.dataDir = Path.of(cli.getString("data"));
            c.policy = cli.getString("policy");
            c.resilient = cli.getBool("resilient");
            c.telemetry = cli.getString("telemetry");
            c.healthInterval = cli.getDuration("health-interval");
            GatewayNode node = new GatewayNode(c);
            node.start();
            System.out.println("gateway " + c.id + ": data=" + c.dataAddr + " control=" + c.peers.get(c.id)
                    + " policy=" + c.policy + " resilient=" + c.resilient + " telemetry=" + c.telemetry);
            System.out.flush();
            awaitShutdown("gateway " + c.id, node::stop);
        } catch (IllegalArgumentException | java.io.IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void backend(String[] args) throws Exception {
        Cli cli = parse(new Cli("aegismesh backend")
                .string("id", "w1", "worker id (reported in the X-Worker header)")
                .string("listen", "127.0.0.1:7001", "address to listen on")
                .integer("capacity", 8, "concurrent requests served at full speed")
                .number("service-ms", 10, "base service time per request")
                .number("extra-ms", 0, "extra latency per request")
                .number("error-rate", 0, "probability of answering 500"), args);
        Worker w = new Worker(cli.getString("id"), new Worker.Config(cli.getInt("capacity"),
                cli.getNumber("service-ms"), 1, cli.getNumber("extra-ms"), cli.getNumber("error-rate"), 0, -1));
        try {
            w.start(cli.getString("listen"));
        } catch (IllegalArgumentException | java.io.IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
        System.out.println("backend " + w.id + ": listening on " + w.addr());
        System.out.flush();
        awaitShutdown("backend " + w.id, w::kill);
    }

    static Duration seconds(double s) {
        return Duration.ofMillis((long) (s * 1000));
    }
}
