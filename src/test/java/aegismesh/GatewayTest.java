package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.breaker.Breaker;
import aegismesh.lb.Backend;
import aegismesh.lb.EventLog;
import aegismesh.lb.Gateway;
import aegismesh.lb.ProxyRequest;
import aegismesh.lb.Responder;
import aegismesh.lb.Router;
import aegismesh.lb.ScoreConfig;
import aegismesh.lb.Spec;
import aegismesh.sim.Worker;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayTest {
    private final List<Worker> workers = new ArrayList<>();
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        workers.forEach(Worker::kill);
        servers.forEach(s -> s.stop(0));
    }

    /** What the gateway delivered to the "client". */
    private static final class Capture implements Responder {
        int status = -1;
        Map<String, List<String>> headers = Map.of();
        String body = "";

        @Override
        public void send(int s, Map<String, List<String>> h, byte[] b) {
            status = s;
            headers = h;
            body = new String(b, StandardCharsets.UTF_8).trim();
        }
    }

    private Worker worker(String id, Worker.Config c) throws IOException {
        Worker w = new Worker(id, c);
        w.start("127.0.0.1:0");
        workers.add(w);
        return w;
    }

    private Worker worker(String id) throws IOException {
        return worker(id, new Worker.Config().withServiceMs(1));
    }

    private static Router router(String policy, List<Spec> specs, Breaker.Config bc) {
        Router r = new Router(ScoreConfig.defaults(), bc, new EventLog(), policy);
        r.reconcile(specs);
        return r;
    }

    private static Spec spec(String id, String addr) {
        return new Spec(id, "api", addr, 0);
    }

    private static final String DEAD = "127.0.0.1:" + 1; // nothing listens on port 1

    /** HTTP header names are case-insensitive and the JDK normalises their capitalisation; compare accordingly. */
    private static String hdr(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static ProxyRequest get(String rawPath) {
        return new ProxyRequest("GET", rawPath, null, Map.of(), new byte[0]);
    }

    private static Capture call(Gateway g, ProxyRequest r) {
        Capture c = new Capture();
        g.handle(r, c);
        return c;
    }

    /** A backend that records exactly what it received. */
    private record Recorded(String method, String rawPath, String rawQuery, String body, Map<String, List<String>> headers) {}

    private List<Recorded> recorder(String[] addrOut, int status) throws IOException {
        List<Recorded> seen = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            seen.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    new java.util.HashMap<>(ex.getRequestHeaders())));
            ex.getResponseHeaders().set("X-Backend-Header", "yes");
            byte[] b = "echo".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        servers.add(s);
        addrOut[0] = "127.0.0.1:" + s.getAddress().getPort();
        return seen;
    }

    // ---------------------------------------------------------------- forwarding

    @Test
    void stripsTheServicePrefixAndForwardsQueryBodyAndHeaders() throws Exception {
        String[] addr = new String[1];
        List<Recorded> seen = recorder(addr, 201);
        Gateway g = new Gateway(router("roundrobin", List.of(spec("a", addr[0])), Breaker.Config.defaults()), true);
        Capture c = new Capture();
        g.handle(new ProxyRequest("POST", "/api/items/7", "x=1&y=two", Map.of("X-Custom", List.of("v1"),
                "Connection", List.of("close"), "Host", List.of("evil.example")), "payload".getBytes(StandardCharsets.UTF_8)), c);
        assertEquals(201, c.status);
        assertEquals("echo", c.body);
        assertEquals("yes", hdr(c.headers, "X-Backend-Header"));
        assertEquals("a", hdr(c.headers, "X-Aegis-Backend"));
        Recorded r = seen.get(0);
        assertEquals("POST", r.method());
        assertEquals("/items/7", r.rawPath());
        assertEquals("x=1&y=two", r.rawQuery());
        assertEquals("payload", r.body());
        assertEquals("v1", hdr(r.headers(), "X-Custom"));
        assertFalse(hdr(r.headers(), "Host").contains("evil"), "the Host header must describe the backend, not the client's value");
    }

    @Test
    void percentEncodedPathSegmentsReachTheBackendVerbatim() throws Exception {
        String[] addr = new String[1];
        List<Recorded> seen = recorder(addr, 200);
        Gateway g = new Gateway(router("roundrobin", List.of(spec("a", addr[0])), Breaker.Config.defaults()), true);
        // regression: an encoded "?" used to turn into a real query string
        call(g, get("/api/files/a%3Fb%3Dc"));
        call(g, get("/api/files/x%2Fy"));
        call(g, get("/api/files/sp%20ace"));
        call(g, new ProxyRequest("GET", "/api/plain", "real=1", Map.of(), new byte[0]));
        call(g, get("/api"));
        call(g, get("/api/"));
        assertEquals(List.of("/files/a%3Fb%3Dc", "/files/x%2Fy", "/files/sp%20ace", "/plain", "/", "/"),
                seen.stream().map(Recorded::rawPath).toList());
        assertEquals(null, seen.get(0).rawQuery());
        assertEquals("real=1", seen.get(3).rawQuery());
    }

    @Test
    void missingServiceIs404AndUnknownServiceIs503() throws Exception {
        Gateway g = new Gateway(router("roundrobin", List.of(spec("a", worker("a").addr())), Breaker.Config.defaults()), true);
        assertEquals(404, call(g, get("/")).status);
        assertEquals(404, call(g, get("")).status);
        Capture c = call(g, get("/nosuch/x"));
        assertEquals(503, c.status);
        assertTrue(c.body.contains("nosuch"));
    }

    @Test
    void anEmptyPoolOrNoCandidatesGives503() {
        Gateway g = new Gateway(router("roundrobin", List.of(), Breaker.Config.defaults()), true);
        assertEquals(503, call(g, get("/api/x")).status);
    }

    // ---------------------------------------------------------------- resilience

    @Test
    void resilientGatewayHidesADeadBackendBehindARetry() throws Exception {
        Router r = router("roundrobin", List.of(spec("dead", DEAD), spec("ok", worker("ok").addr())), Breaker.Config.defaults());
        Gateway g = new Gateway(r, true);
        for (int i = 0; i < 60; i++) {
            Capture c = call(g, get("/api/x"));
            assertEquals(200, c.status, "request " + i);
            assertEquals("ok", hdr(c.headers, "X-Aegis-Backend"));
        }
    }

    @Test
    void theBreakerOpensOnTheDeadBackendSoItStopsReceivingTraffic() throws Exception {
        Breaker.Config bc = new Breaker.Config(5, Duration.ofMinutes(5), 1, 2); // long open time: no probe mid-test
        Router r = router("roundrobin", List.of(spec("dead", DEAD), spec("ok", worker("ok").addr())), bc);
        Gateway g = new Gateway(r, true);
        for (int i = 0; i < 40; i++) {
            call(g, get("/api/x"));
        }
        Backend dead = r.all().stream().filter(b -> b.id.equals("dead")).findFirst().orElseThrow();
        assertEquals(Breaker.State.OPEN, dead.breaker.state());
        long attempts = dead.requests.get();
        for (int i = 0; i < 40; i++) {
            call(g, get("/api/x"));
        }
        assertEquals(attempts, dead.requests.get(), "an open breaker must keep traffic away");
    }

    @Test
    void aNaiveGatewayDoesNotRetry() throws Exception {
        Router r = router("roundrobin", List.of(spec("dead", DEAD), spec("ok", worker("ok").addr())), Breaker.Config.defaults());
        Gateway g = new Gateway(r, false);
        int bad = 0;
        for (int i = 0; i < 20; i++) {
            Capture c = call(g, get("/api/x"));
            if (c.status == 502) {
                bad++;
            }
        }
        assertEquals(10, bad, "round-robin without resilience sends half the traffic to the dead backend");
    }

    @Test
    void nonIdempotentRequestsAreNeverRetried() throws Exception {
        Router r = router("roundrobin", List.of(spec("dead", DEAD), spec("ok", worker("ok").addr())), Breaker.Config.defaults());
        Gateway g = new Gateway(r, true);
        int bad = 0;
        for (int i = 0; i < 10; i++) {
            Capture c = new Capture();
            g.handle(new ProxyRequest("POST", "/api/x", null, Map.of(), "b".getBytes(StandardCharsets.UTF_8)), c);
            if (c.status == 502) {
                bad++;
            }
        }
        assertTrue(bad >= 1, "a POST that hit the dead backend must fail rather than be replayed");
        assertTrue(r.all().stream().filter(b -> b.id.equals("dead")).findFirst().orElseThrow().requests.get() >= 1);
    }

    @Test
    void whenEveryBackendFailsWithA500TheLastOneIsRelayed() throws Exception {
        Worker a = worker("a", new Worker.Config().withServiceMs(1).withErrorRate(1));
        Worker b = worker("b", new Worker.Config().withServiceMs(1).withErrorRate(1));
        Gateway g = new Gateway(router("roundrobin", List.of(spec("a", a.addr()), spec("b", b.addr())),
                Breaker.Config.defaults()), true);
        Capture c = call(g, get("/api/x"));
        assertEquals(500, c.status);
        assertEquals("injected error", c.body);
        assertEquals(1, a.served.get() + b.served.get() - 1, "exactly one retry: two attempts in total");
    }

    @Test
    void aSlowBackendTimesOutAndIsRetriedElsewhere() throws Exception {
        Worker slow = worker("slow", new Worker.Config().withServiceMs(1).withExtraMs(1500));
        Worker fast = worker("fast");
        Router r = router("roundrobin", List.of(spec("a-slow", slow.addr()), spec("b-fast", fast.addr())), Breaker.Config.defaults());
        Gateway g = new Gateway(r, true, Duration.ofMillis(200));
        for (int i = 0; i < 4; i++) {
            Capture c = call(g, get("/api/x"));
            assertEquals(200, c.status);
            assertEquals("b-fast", hdr(c.headers, "X-Aegis-Backend"));
        }
        Gateway naive = new Gateway(r, false, Duration.ofMillis(200));
        int bad = 0;
        for (int i = 0; i < 4; i++) {
            if (call(naive, get("/api/x")).status == 502) {
                bad++;
            }
        }
        assertEquals(2, bad, "without retries, round-robin hands half the requests to the slow backend and they time out");
    }

    @Test
    void inflightReturnsToZeroAndCountersAreMaintained() throws Exception {
        Router r = router("roundrobin", List.of(spec("a", worker("a").addr())), Breaker.Config.defaults());
        Gateway g = new Gateway(r, true);
        for (int i = 0; i < 25; i++) {
            call(g, get("/api/x"));
        }
        Backend b = r.all().get(0);
        assertEquals(0, b.inflight());
        assertEquals(25, b.requests.get());
        assertEquals(0, b.failuresTotal.get());
        assertTrue(b.snapshot(r.score, System.nanoTime()).ewmaMs() > 0);
    }

    // ---------------------------------------------------------------- client disconnects

    /** Regression: a client that hung up used to be reported to the breaker as a success. */
    @Test
    void aVanishedClientNeitherBlamesNorCreditsTheBackend() throws Exception {
        Breaker.Config bc = new Breaker.Config(1, Duration.ofMillis(60), 1, 1);
        Router r = router("roundrobin", List.of(spec("a", worker("a").addr())), bc);
        Backend a = r.all().get(0);
        a.breaker.forceOpen();
        Thread.sleep(120);
        assertEquals(Breaker.State.HALF_OPEN, a.breaker.state());
        Gateway g = new Gateway(r, true);
        g.handle(get("/api/x"), (s, h, b) -> {
            throw new IOException("client went away");
        });
        assertEquals(Breaker.State.HALF_OPEN, a.breaker.state(), "a cancelled probe must not close the breaker");
        assertTrue(a.breaker.admits(), "...and must release its probe slot");
        assertEquals(0, a.failuresTotal.get());
        assertEquals(0.0, a.snapshot(r.score, System.nanoTime()).ewmaMs(), 1e-9, "no latency sample from an abandoned request");
        assertEquals(200, call(g, get("/api/x")).status);
        assertEquals(Breaker.State.CLOSED, a.breaker.state(), "a real success closes it");
    }

    @Test
    void aVanishedClientDuringAFailureDoesNotCountAgainstTheBackendEither() throws Exception {
        Router r = router("roundrobin", List.of(spec("dead", DEAD)), new Breaker.Config(2, Duration.ofSeconds(5), 1, 1));
        Gateway g = new Gateway(r, false);
        AtomicInteger sends = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            g.handle(get("/api/x"), (s, h, b) -> {
                sends.incrementAndGet();
                throw new IOException("gone");
            });
        }
        assertEquals(5, sends.get());
        assertEquals(Breaker.State.CLOSED, r.all().get(0).breaker.state());
    }
}
