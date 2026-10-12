package aegismesh.control;

import aegismesh.lb.Spec;
import aegismesh.raft.RaftHttp;
import aegismesh.raft.Types.Status;
import aegismesh.util.HttpUtil;
import aegismesh.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-plane HTTP server: Raft RPCs ({@code /raft/*}) and the admin API ({@code /admin/*}).
 *
 * <p>Status codes: 200 {@code committed}; 400 invalid input (nothing is written to the log); 405 wrong method
 * (with {@code Allow: POST}); 503 no leader / quorum unavailable. These endpoints are <strong>not
 * authenticated</strong>: bind them to a private interface.
 */
public final class ControlServer {
    private static final int MAX_BODY = 1 << 20;
    private final Plane plane;
    private final HttpServer server;

    public ControlServer(String listenAddr, Plane plane) throws IOException {
        this.plane = plane;
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

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            switch (path) {
                case "/raft/vote", "/raft/append" -> raft(ex, path);
                case "/admin/command" -> {
                    if (post(ex)) {
                        Command c = decodeCommand(ex);
                        if (c != null) {
                            submit(ex, c);
                        }
                    }
                }
                case "/admin/backends" -> {
                    if (post(ex)) {
                        Map<String, Object> m = decodeObject(ex);
                        if (m != null) {
                            submit(ex, Command.addBackend(Spec.fromMap(m)));
                        }
                    }
                }
                case "/admin/backends/remove" -> {
                    if (post(ex)) {
                        String id = queryParam(ex.getRequestURI().getRawQuery(), "id");
                        if (id.isEmpty()) {
                            HttpUtil.text(ex, 400, "POST ?id=<backend>");
                        } else {
                            submit(ex, Command.removeBackend(id));
                        }
                    }
                }
                case "/admin/policy" -> {
                    if (post(ex)) {
                        Map<String, Object> m = decodeObject(ex);
                        if (m != null) {
                            submit(ex, Command.setPolicy(Json.str(m, "service", ""), Json.str(m, "policy", "")));
                        }
                    }
                }
                case "/admin/status" -> {
                    if (HttpUtil.requireMethod(ex, "GET", "HEAD")) {
                        HttpUtil.json(ex, 200, status());
                    }
                }
                default -> HttpUtil.text(ex, 404, "not found");
            }
        } catch (Json.JsonException e) {
            HttpUtil.text(ex, 400, "bad json: " + e.getMessage());
        } catch (RuntimeException e) {
            HttpUtil.text(ex, 500, "internal error: " + e.getClass().getSimpleName());
        }
    }

    private void raft(HttpExchange ex, String path) throws IOException {
        if (!post(ex)) {
            return;
        }
        String body = new String(HttpUtil.readBody(ex, MAX_BODY), StandardCharsets.UTF_8);
        try {
            String reply = RaftHttp.handle(plane.node, path, body);
            HttpUtil.send(ex, 200, "application/json", reply.getBytes(StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            HttpUtil.text(ex, 400, "bad request");
        }
    }

    private static boolean post(HttpExchange ex) throws IOException {
        return HttpUtil.requireMethod(ex, "POST");
    }

    private static Map<String, Object> decodeObject(HttpExchange ex) throws IOException {
        try {
            return Json.obj(Json.parse(new String(HttpUtil.readBody(ex, MAX_BODY), StandardCharsets.UTF_8)));
        } catch (Json.JsonException e) {
            HttpUtil.text(ex, 400, "bad json: " + e.getMessage());
            return null;
        }
    }

    private static Command decodeCommand(HttpExchange ex) throws IOException {
        try {
            return Command.fromJson(new String(HttpUtil.readBody(ex, MAX_BODY), StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            HttpUtil.text(ex, 400, "bad json: " + e.getMessage());
            return null;
        }
    }

    private void submit(HttpExchange ex, Command c) throws IOException {
        try {
            boolean forwarded = ex.getRequestHeaders().getFirst("X-Aegis-Forwarded") != null;
            plane.submit(c, Duration.ofSeconds(4), forwarded);
            HttpUtil.text(ex, 200, "committed");
        } catch (ControlException e) {
            HttpUtil.text(ex, e.invalid() ? 400 : 503, e.getMessage());
        }
    }

    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return "";
        }
        for (String kv : rawQuery.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (k.equals(name)) {
                return i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private Map<String, Object> status() {
        Status s = plane.node.status();
        Map<String, Object> raft = new LinkedHashMap<>();
        raft.put("id", s.id());
        raft.put("role", s.role().toString());
        raft.put("term", s.term());
        raft.put("leader", s.leader());
        raft.put("commit_index", s.commitIndex());
        raft.put("last_applied", s.lastApplied());
        raft.put("last_index", s.lastIndex());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raft", raft);
        m.put("state", plane.fsm.snapshot().toMap());
        return m;
    }
}
