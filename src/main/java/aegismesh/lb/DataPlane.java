package aegismesh.lb;

import aegismesh.util.HttpUtil;
import aegismesh.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The client-facing HTTP server. Reserved paths (so they cannot be service names): {@code /healthz} liveness,
 * {@code /readyz} readiness, {@code /metrics} Prometheus text, {@code /_aegis/events} the event log. Everything else
 * is proxied by the {@link Gateway}.
 */
public final class DataPlane {
    private final Gateway gateway;
    private final Supplier<Readiness> readiness;
    private final HttpServer server;

    public DataPlane(String listenAddr, Gateway gateway, Supplier<Readiness> readiness) throws IOException {
        this.gateway = gateway;
        this.readiness = readiness;
        InetSocketAddress addr = HttpUtil.parseAddr(listenAddr);
        this.server = HttpUtil.newServer(addr);
        this.server.createContext("/", this::dispatch);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            switch (path == null ? "" : path) {
                case "/healthz" -> {
                    if (HttpUtil.requireMethod(ex, "GET", "HEAD")) {
                        HttpUtil.send(ex, 200, "application/json", "{\"status\":\"ok\"}\n".getBytes());
                    }
                }
                case "/readyz" -> {
                    if (HttpUtil.requireMethod(ex, "GET", "HEAD")) {
                        Readiness r = readiness.get();
                        HttpUtil.json(ex, r.ready() ? 200 : 503, r.toMap());
                    }
                }
                case "/metrics" -> {
                    if (HttpUtil.requireMethod(ex, "GET", "HEAD")) {
                        HttpUtil.send(ex, 200, "text/plain; version=0.0.4", metrics(gateway.router()).getBytes());
                    }
                }
                case "/_aegis/events" -> {
                    if (HttpUtil.requireMethod(ex, "GET", "HEAD")) {
                        HttpUtil.json(ex, 200, events(gateway.router()));
                    }
                }
                default -> proxy(ex);
            }
        } catch (RuntimeException e) {
            try {
                HttpUtil.text(ex, 500, "internal error: " + e.getClass().getSimpleName());
            } catch (IOException ignored) {
                ex.close();
            }
        }
    }

    private void proxy(HttpExchange ex) throws IOException {
        byte[] body;
        try {
            body = HttpUtil.readBody(ex, 16 << 20);
        } catch (IOException e) {
            HttpUtil.text(ex, 413, "request body too large");
            return;
        }
        ProxyRequest req = new ProxyRequest(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                ex.getRequestURI().getRawQuery(), new LinkedHashMap<>(ex.getRequestHeaders()), body);
        gateway.handle(req, (status, headers, payload) -> {
            headers.forEach((k, v) -> ex.getResponseHeaders().put(k, new ArrayList<>(v)));
            boolean empty = payload.length == 0 || "HEAD".equals(ex.getRequestMethod());
            ex.sendResponseHeaders(status, empty ? -1 : payload.length);
            if (!empty) {
                ex.getResponseBody().write(payload);
            }
            ex.close();
        });
        ex.close(); // no-op if the responder already closed it (also covers a client that vanished)
    }

    static String metrics(Router r) {
        StringBuilder sb = new StringBuilder();
        long now = System.nanoTime();
        for (Backend b : r.all()) {
            Signals s = b.snapshot(r.score, now);
            String l = String.format(Locale.ROOT, "{service=\"%s\",backend=\"%s\",state=\"%s\"}", s.service(), s.id(), s.state());
            sb.append(String.format(Locale.ROOT, "aegis_backend_healthy%s %d%n", l, s.healthy() ? 1 : 0));
            sb.append(String.format(Locale.ROOT, "aegis_backend_ewma_latency_ms%s %.3f%n", l, s.ewmaMs()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_error_rate%s %.4f%n", l, s.errRate()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_inflight%s %d%n", l, s.inflight()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_cpu%s %.3f%n", l, s.cpu()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_kernel_penalty%s %.3f%n", l, s.kernelPen()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_cost%s %.3f%n", l, s.cost()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_requests_total%s %d%n", l, s.requests()));
            sb.append(String.format(Locale.ROOT, "aegis_backend_failures_total%s %d%n", l, s.failures()));
        }
        return sb.toString();
    }

    static List<Object> events(Router r) {
        List<Object> out = new ArrayList<>();
        for (EventLog.Event e : r.events.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at().toString());
            m.put("kind", e.kind());
            m.put("backend", e.backend());
            m.put("detail", e.detail());
            out.add(m);
        }
        return out;
    }

    /** Parses a JSON events document back (used by tests and the benchmark). */
    public static List<Object> parseEvents(String json) {
        return Json.arr(Json.parse(json));
    }
}
