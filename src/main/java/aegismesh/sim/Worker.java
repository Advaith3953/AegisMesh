package aegismesh.sim;

import aegismesh.util.HttpUtil;
import aegismesh.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * A simulated backend server for tests, demos and benchmarks. It has a fixed number of concurrent "slots", so load
 * beyond capacity queues (that queueing is what makes a loaded worker slow), and faults that can be changed while it
 * runs: extra latency, error rate, slow service, packet-loss stalls and a reported CPU level.
 *
 * <p>Endpoints: {@code GET /healthz} (status, cpu, inflight), {@code POST /_fault} (JSON, changes faults), anything
 * else is "work".
 */
public final class Worker {
    /**
     * @param capacity concurrent requests served at full speed
     * @param serviceMs base service time per request
     * @param serviceMul multiplier on the service time (slow worker)
     * @param extraMs fixed extra delay per request (latency injection)
     * @param errorRate probability of answering 500
     * @param lossRate probability of a 200 ms retransmission-style stall
     * @param cpu CPU level reported by /healthz; negative means "derive it from slot usage"
     */
    public record Config(int capacity, double serviceMs, double serviceMul, double extraMs, double errorRate,
                         double lossRate, double cpu) {
        public Config {
            capacity = capacity <= 0 ? 8 : capacity;
            serviceMs = serviceMs <= 0 ? 10 : serviceMs;
            serviceMul = serviceMul <= 0 ? 1 : serviceMul;
        }

        public Config() {
            this(0, 0, 0, 0, 0, 0, -1);
        }

        public Config withCapacity(int v) {
            return new Config(v, serviceMs, serviceMul, extraMs, errorRate, lossRate, cpu);
        }

        public Config withServiceMs(double v) {
            return new Config(capacity, v, serviceMul, extraMs, errorRate, lossRate, cpu);
        }

        public Config withServiceMul(double v) {
            return new Config(capacity, serviceMs, v, extraMs, errorRate, lossRate, cpu);
        }

        public Config withExtraMs(double v) {
            return new Config(capacity, serviceMs, serviceMul, v, errorRate, lossRate, cpu);
        }

        public Config withErrorRate(double v) {
            return new Config(capacity, serviceMs, serviceMul, extraMs, v, lossRate, cpu);
        }

        public Config withLossRate(double v) {
            return new Config(capacity, serviceMs, serviceMul, extraMs, errorRate, v, cpu);
        }

        public Config withCpu(double v) {
            return new Config(capacity, serviceMs, serviceMul, extraMs, errorRate, lossRate, v);
        }
    }

    public final String id;
    public final AtomicLong served = new AtomicLong();
    private volatile Config cfg;
    private Semaphore slots;
    private final AtomicLong inflight = new AtomicLong();
    private HttpServer server;
    private InetSocketAddress bound;

    public Worker(String id, Config cfg) {
        this.id = id;
        this.cfg = cfg;
        this.slots = new Semaphore(cfg.capacity(), true);
    }

    public Config config() {
        return cfg;
    }

    /** Atomically replaces the fault configuration (a changed capacity takes effect on the next restart). */
    public synchronized void update(UnaryOperator<Config> f) {
        this.cfg = f.apply(cfg);
    }

    /** Starts listening on {@code host:port} (port 0 picks a free one). */
    public synchronized void start(String hostPort) throws IOException {
        if (server != null) {
            throw new IllegalStateException("worker already running");
        }
        listen(HttpUtil.parseAddr(hostPort));
    }

    private void listen(InetSocketAddress addr) throws IOException {
        HttpServer s = HttpUtil.newServer(addr);
        s.createContext("/", this::handle);
        s.start();
        this.server = s;
        this.bound = s.getAddress();
    }

    /** {@code host:port} actually bound. */
    public synchronized String addr() {
        return bound.getHostString() + ":" + bound.getPort();
    }

    /** Stops serving, dropping connections (a crash). Idempotent. */
    public synchronized void kill() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Starts again on the same address after {@link #kill()}. */
    public synchronized void restart() throws IOException {
        if (server != null) {
            return;
        }
        slots = new Semaphore(cfg.capacity(), true);
        listen(bound);
    }

    boolean chance(double p) {
        return p > 0 && ThreadLocalRandom.current().nextDouble() < p;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/healthz".equals(path)) {
            health(ex);
        } else if ("/_fault".equals(path)) {
            fault(ex);
        } else {
            work(ex);
        }
    }

    private void health(HttpExchange ex) throws IOException {
        Config c = cfg;
        double cpu = c.cpu();
        if (cpu < 0) {
            cpu = (double) (c.capacity() - slots.availablePermits()) / c.capacity();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("cpu", cpu);
        m.put("inflight", inflight.get());
        HttpUtil.json(ex, 200, m);
    }

    private void fault(HttpExchange ex) throws IOException {
        if (!HttpUtil.requireMethod(ex, "POST")) {
            return;
        }
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(new String(HttpUtil.readBody(ex, 1 << 16), StandardCharsets.UTF_8)));
            update(c -> {
                Config n = c;
                if (m.containsKey("extra_ms")) {
                    n = n.withExtraMs(Json.dbl(m, "extra_ms", 0));
                }
                if (m.containsKey("error_rate")) {
                    n = n.withErrorRate(Json.dbl(m, "error_rate", 0));
                }
                if (m.containsKey("loss_rate")) {
                    n = n.withLossRate(Json.dbl(m, "loss_rate", 0));
                }
                if (m.containsKey("service_mul")) {
                    n = n.withServiceMul(Json.dbl(m, "service_mul", 1));
                }
                if (m.containsKey("cpu")) {
                    n = n.withCpu(Json.dbl(m, "cpu", -1));
                }
                return n;
            });
        } catch (Json.JsonException e) {
            HttpUtil.text(ex, 400, "body must be a JSON object: " + e.getMessage());
            return;
        }
        HttpUtil.json(ex, 200, Map.of("ok", true));
    }

    private void work(HttpExchange ex) throws IOException {
        HttpUtil.readBody(ex, 1 << 20);
        Semaphore s = slots;
        inflight.incrementAndGet();
        boolean acquired = false;
        try {
            s.acquire();
            acquired = true;
            Config c = cfg;
            double ms = c.serviceMs() * c.serviceMul() * (0.9 + 0.2 * ThreadLocalRandom.current().nextDouble())
                    + c.extraMs();
            if (chance(c.lossRate())) {
                ms += 200; // a retransmission timeout
            }
            Thread.sleep((long) Math.max(0, ms));
            ex.getResponseHeaders().set("X-Worker", id);
            served.incrementAndGet(); // count before answering: a client must never see a response the counter has not recorded
            if (chance(c.errorRate())) {
                HttpUtil.text(ex, 500, "injected error");
            } else {
                HttpUtil.text(ex, 200, "ok");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.close();
        } finally {
            if (acquired) {
                s.release();
            }
            inflight.decrementAndGet();
        }
    }
}
