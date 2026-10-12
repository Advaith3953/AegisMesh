package aegismesh.lb;

import aegismesh.breaker.Breaker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Reverse proxy. A request to {@code /<service>/<rest>} is routed to a backend of {@code service} and forwarded as
 * {@code /<rest>}. In resilient mode it also skips ejected backends, honours circuit breakers and retries an
 * idempotent request once on a different backend.
 */
public final class Gateway {
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade");
    // headers java.net.http refuses to let callers set; it manages them itself
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "expect", "http2-settings");

    private final Router router;
    private final boolean resilient;
    private final Duration attemptTimeout;
    private final int maxRetries;
    private final HttpClient client;

    public Gateway(Router router, boolean resilient) {
        this(router, resilient, Duration.ofSeconds(1));
    }

    public Gateway(Router router, boolean resilient, Duration attemptTimeout) {
        this.router = router;
        this.resilient = resilient;
        this.attemptTimeout = attemptTimeout;
        this.maxRetries = resilient ? 1 : 0;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    }

    public Router router() {
        return router;
    }

    /** Splits {@code /svc/rest} into ("svc", "/rest"); {@code /svc} gives ("svc", "/"). */
    static String[] splitService(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int i = p.indexOf('/');
        if (i >= 0) {
            return new String[] {p.substring(0, i), p.substring(i)};
        }
        return new String[] {p, "/"};
    }

    private static boolean idempotent(String m) {
        return m.equals("GET") || m.equals("HEAD");
    }

    private record Result(int status, Map<String, List<String>> headers, byte[] body, Exception error,
                          long elapsedNanos) {}

    /** Routes one request and delivers exactly one answer through {@code out}. */
    public void handle(ProxyRequest req, Responder out) {
        String[] sr = splitService(req.rawPath());
        String service = sr[0];
        String rest = sr[1];
        if (service.isEmpty()) {
            reply(out, 404, "missing service");
            return;
        }
        Set<Backend> tried = new HashSet<>();
        int retries = idempotent(req.method()) ? maxRetries : 0;
        int spins = 0;
        for (int attempt = 0;;) {
            Backend b = router.pick(service, resilient, tried).backend();
            if (b == null) {
                reply(out, 503, "no available backend for " + service);
                return;
            }
            Breaker.Ticket tk = null;
            if (resilient) {
                Optional<Breaker.Ticket> t = b.breaker.allow();
                if (t.isEmpty()) { // raced with another request for the half-open probe slot
                    tried.add(b);
                    if (++spins > 8) {
                        reply(out, 503, "no available backend for " + service);
                        return;
                    }
                    continue;
                }
                tk = t.get();
            }
            boolean last = attempt >= retries;
            Result r = forward(req, b, rest);
            boolean failed = r.error() != null || r.status() >= 500;

            boolean clientGone = false;
            if (r.error() == null && (r.status() < 500 || last)) {
                Map<String, List<String>> h = new LinkedHashMap<>(r.headers());
                h.put("X-Aegis-Backend", List.of(b.id));
                try {
                    out.send(r.status(), h, r.body());
                } catch (IOException e) {
                    clientGone = true;
                }
            }
            if (!clientGone) { // never blame the backend for a client that left
                b.observe(router.score, r.elapsedNanos(), !failed, System.nanoTime());
                if (failed) {
                    b.failuresTotal.incrementAndGet();
                }
            }
            if (tk != null) {
                if (clientGone) {
                    b.breaker.cancel(tk); // no evidence about the backend either way
                } else {
                    b.breaker.report(tk, !failed);
                }
            }
            if (clientGone || !failed) {
                return;
            }
            if (last) {
                if (r.error() != null) {
                    reply(out, 502, "bad gateway: " + describe(r.error()));
                }
                return; // else: a final 5xx was already relayed to the client
            }
            tried.add(b);
            attempt++;
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static void reply(Responder out, int status, String msg) {
        try {
            out.send(status, Map.of("Content-Type", List.of("text/plain; charset=utf-8")),
                    (msg + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // client already gone
        }
    }

    private Result forward(ProxyRequest req, Backend b, String rest) {
        b.inflight.incrementAndGet();
        b.requests.incrementAndGet();
        long start = System.nanoTime();
        try {
            String target = "http://" + b.addr + rest + (req.rawQuery() == null || req.rawQuery().isEmpty()
                    ? "" : "?" + req.rawQuery());
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target)).timeout(attemptTimeout);
            for (Map.Entry<String, List<String>> h : req.headers().entrySet()) {
                String k = h.getKey().toLowerCase(Locale.ROOT);
                if (HOP.contains(k) || RESTRICTED.contains(k)) {
                    continue;
                }
                for (String v : h.getValue()) {
                    rb.header(h.getKey(), v);
                }
            }
            rb.method(req.method(), req.body().length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(req.body()));
            HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, List<String>> headers = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) -> {
                String lk = k.toLowerCase(Locale.ROOT);
                if (!HOP.contains(lk) && !lk.equals("content-length") && !k.startsWith(":")) {
                    headers.put(k, v);
                }
            });
            return new Result(resp.statusCode(), headers, resp.body(), null, System.nanoTime() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, Map.of(), new byte[0], e, System.nanoTime() - start);
        } catch (IOException | RuntimeException e) {
            return new Result(0, Map.of(), new byte[0], e, System.nanoTime() - start);
        } finally {
            b.inflight.decrementAndGet();
        }
    }
}
