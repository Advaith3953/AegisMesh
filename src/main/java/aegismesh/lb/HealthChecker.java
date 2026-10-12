package aegismesh.lb;

import aegismesh.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Active health checking: every interval, each backend's {@code GET /healthz} is probed. After {@code failThreshold}
 * consecutive failures the backend is ejected (and its breaker forced open); after {@code passThreshold}
 * consecutive successes it is re-introduced. The probe also carries the backend's reported CPU pressure.
 */
public final class HealthChecker {
    private final Router router;
    private final Duration interval;
    private final Duration timeout;
    private final int failThreshold;
    private final int passThreshold;
    private ScheduledExecutorService scheduler;
    private volatile boolean stopped;

    public HealthChecker(Router router, Duration interval) {
        this(router, interval, 2, 2);
    }

    public HealthChecker(Router router, Duration interval, int failThreshold, int passThreshold) {
        this.router = router;
        this.interval = interval;
        this.timeout = interval.multipliedBy(2);
        this.failThreshold = failThreshold;
        this.passThreshold = passThreshold;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        stopped = false;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::round, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        stopped = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    /** One probing round over all backends (public so tests can drive it deterministically). */
    public void round() {
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (Backend b : router.all()) {
                fs.add(vt.submit(() -> probe(b)));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // a probe never throws; nothing to do
                }
            }
        }
    }

    private void probe(Backend b) {
        boolean ok = false;
        double cpu = 0;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create("http://" + b.addr + "/healthz").toURL().openConnection();
            c.setConnectTimeout((int) timeout.toMillis());
            c.setReadTimeout((int) timeout.toMillis());
            c.setRequestProperty("Connection", "close"); // a fresh connection each time: a restarted backend must not look dead
            if (c.getResponseCode() == 200) {
                try (InputStream in = c.getInputStream()) {
                    Map<String, Object> rep = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                    cpu = Json.dbl(rep, "cpu", 0);
                    ok = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            ok = false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        if (stopped) {
            return;
        }
        int act = b.recordProbe(ok, cpu, failThreshold, passThreshold);
        if (act > 0) {
            b.setHealthy(false);
            b.breaker.forceOpen();
            router.events.emit("ejected", b.id, "health check failed");
        } else if (act < 0) {
            b.breaker.reset();
            b.setHealthy(true);
            router.events.emit("reintroduced", b.id, "health check passed");
        }
    }
}
