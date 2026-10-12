package aegismesh.lb;

import aegismesh.breaker.Breaker;
import java.util.concurrent.atomic.AtomicLong;

/** One worker instance with its live routing signals. All mutable scoring state is guarded by {@code this}. */
public final class Backend {
    private static final long UNSET = Long.MIN_VALUE;

    public final String id;
    public final String service;
    public final String addr; // host:port
    public final Breaker breaker;

    final AtomicLong inflight = new AtomicLong();
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong failuresTotal = new AtomicLong();
    private volatile boolean healthy = true;

    private int weight = 1;
    private double ewmaMs;
    private boolean seen;
    private double errRate;
    private double failScore; // decayed count of recent failures
    private long failAt = UNSET;
    private double cpu; // 0..1, from the active health check
    private double kernelPen; // >= 0, from kernel telemetry
    private int failStreak;
    private int passStreak;

    public Backend(String id, String service, String addr, Breaker.Config bc) {
        this.id = id;
        this.service = service;
        this.addr = addr;
        this.breaker = new Breaker(bc);
    }

    /** Changes the capacity weight (values below 1 mean the default, 1). Safe while requests are being scored. */
    public synchronized void setWeight(int w) {
        this.weight = Math.max(1, w);
    }

    public synchronized int weight() {
        return weight;
    }

    public long inflight() {
        return inflight.get();
    }

    public boolean healthy() {
        return healthy;
    }

    void setHealthy(boolean h) {
        this.healthy = h;
    }

    /** Feeds one completed attempt into the EWMA signals. */
    public synchronized void observe(ScoreConfig cfg, long latencyNanos, boolean ok, long nowNanos) {
        double ms = latencyNanos / 1e6;
        decay(cfg, nowNanos);
        if (!seen) {
            ewmaMs = ms;
            seen = true;
        } else {
            ewmaMs = cfg.alpha() * ms + (1 - cfg.alpha()) * ewmaMs;
        }
        double e = 0;
        if (!ok) {
            e = 1;
            failScore++;
        }
        errRate = cfg.alpha() * e + (1 - cfg.alpha()) * errRate;
    }

    private void decay(ScoreConfig cfg, long now) {
        if (failAt == UNSET) {
            failAt = now;
            return;
        }
        long dt = now - failAt;
        if (dt <= 0) {
            return; // concurrent callers may pass slightly stale timestamps; never decay backwards
        }
        if (failScore > 0 && cfg.failHalfLife().toNanos() > 0) {
            failScore *= Math.pow(0.5, (double) dt / cfg.failHalfLife().toNanos());
        }
        failAt = now;
    }

    public synchronized void setCpu(double v) {
        this.cpu = Math.max(0, Math.min(1, v));
    }

    public synchronized void setKernelPenalty(double v) {
        this.kernelPen = Math.max(0, v);
    }

    /** Records a health-probe result; returns {@code +1} to eject, {@code -1} to revive, {@code 0} for no change. */
    synchronized int recordProbe(boolean ok, double reportedCpu, int failThreshold, int passThreshold) {
        if (ok) {
            failStreak = 0;
            passStreak++;
            cpu = Math.max(0, Math.min(1, reportedCpu));
        } else {
            passStreak = 0;
            failStreak++;
        }
        if (!ok && failStreak >= failThreshold && healthy) {
            return 1;
        }
        if (ok && passStreak >= passThreshold && !healthy) {
            return -1;
        }
        return 0;
    }

    /** The formula documented on {@link ScoreConfig}. Lower is better. */
    public synchronized double cost(ScoreConfig cfg, long nowNanos) {
        decay(cfg, nowNanos);
        double lat = seen ? ewmaMs : cfg.initialMs();
        if (lat < cfg.floorMs()) {
            lat = cfg.floorMs();
        }
        double cpuPressure = Math.max(0, cpu - 0.5) / 0.5;
        double c = lat
                * (1 + inflight.get())
                * (1 + cfg.errWeight() * errRate)
                * (1 + cfg.cpuWeight() * cpuPressure)
                * (1 + cfg.failWeight() * failScore)
                * (1 + cfg.kernelWeight() * kernelPen);
        return weight > 1 ? c / weight : c;
    }

    public synchronized Signals snapshot(ScoreConfig cfg, long nowNanos) {
        double cost = cost(cfg, nowNanos);
        return new Signals(id, service, addr, breaker.state().toString(), healthy, ewmaMs, errRate, inflight.get(),
                cpu, kernelPen, failScore, cost, requests.get(), failuresTotal.get());
    }
}
