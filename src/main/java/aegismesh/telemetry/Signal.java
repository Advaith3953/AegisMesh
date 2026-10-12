package aegismesh.telemetry;

/** Kernel-level trouble on live connections to one backend. */
public record Signal(long retrans, long sendQueueBytes) {}
