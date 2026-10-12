package aegismesh.lb;

import java.time.Duration;

/**
 * Tunes the adaptive backend cost function.
 *
 * <pre>
 *   cost = ewmaLatencyMs
 *        × (1 + inflight)                      queueing: work already sent
 *        × (1 + errWeight  × errorRate)        recent error ratio, 0..1
 *        × (1 + cpuWeight  × cpuPressure)      backend-reported CPU, 0..1 (only load above 50 % counts)
 *        × (1 + failWeight × recentFailures)   exponentially decayed failure count
 *        × (1 + kernelWeight × kernelPenalty)  retransmits / send-queue from kernel telemetry
 *        ÷ weight
 * </pre>
 *
 * Terms are multiplied, not added, because they have different units; a multiplicative penalty means "this backend
 * looks N times worse", which is what a router wants to compare. Lower cost is better.
 *
 * @param alpha EWMA smoothing factor for latency and error rate
 * @param initialMs optimistic latency prior for backends never observed
 * @param floorMs lower bound so a 0 ms sample cannot zero the cost
 */
public record ScoreConfig(double alpha, double initialMs, double floorMs, double errWeight, double cpuWeight,
                          double failWeight, double kernelWeight, Duration failHalfLife) {
    public static ScoreConfig defaults() {
        return new ScoreConfig(0.2, 1, 0.5, 20, 2, 0.5, 1, Duration.ofSeconds(5));
    }
}
