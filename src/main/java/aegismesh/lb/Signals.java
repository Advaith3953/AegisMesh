package aegismesh.lb;

/** Point-in-time view of one backend, used by /metrics, readiness and benchmarks. */
public record Signals(String id, String service, String addr, String state, boolean healthy, double ewmaMs,
                      double errRate, long inflight, double cpu, double kernelPen, double recentFailures,
                      double cost, long requests, long failures) {}
