package aegismesh.telemetry;

import aegismesh.lb.Backend;
import aegismesh.lb.Router;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically samples a {@link Source} and turns kernel trouble into a routing penalty per backend:
 * {@code penalty = 0.5 × retransmission timeouts + min(5, unsent bytes / 64 KiB)}. A backend with no matching
 * connection gets penalty 0, so the signal never makes things worse than having no telemetry.
 */
public final class Feeder {
    private final Source source;
    private final Router router;
    private final Duration interval;
    private final AtomicLong samples = new AtomicLong();
    private volatile Exception lastError;
    private ScheduledExecutorService scheduler;

    public Feeder(Source source, Router router, Duration interval) {
        this.source = source;
        this.router = router;
        this.interval = interval;
    }

    public long samples() {
        return samples.get();
    }

    public Exception lastError() {
        return lastError;
    }

    static double penalty(Signal s) {
        return 0.5 * s.retrans() + Math.min(5.0, s.sendQueueBytes() / 65536.0);
    }

    /** Takes one sample and applies it (public so tests can drive it deterministically). */
    public void tick() {
        Map<String, Signal> sig;
        try {
            sig = source.sample();
        } catch (IOException | RuntimeException e) {
            lastError = e;
            return;
        }
        lastError = null;
        samples.incrementAndGet();
        for (Backend b : router.all()) {
            Signal s = sig.get(resolve(b.addr));
            b.setKernelPenalty(s == null ? 0 : penalty(s));
        }
    }

    /** {@code localhost:80} → {@code 127.0.0.1:80}; unresolvable names are returned unchanged. */
    static String resolve(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0) {
            return hostPort;
        }
        try {
            return InetAddress.getByName(hostPort.substring(0, i)).getHostAddress() + hostPort.substring(i);
        } catch (IOException e) {
            return hostPort;
        }
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-feeder");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        source.close();
    }
}
