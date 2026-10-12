package aegismesh.lb;

import java.util.LinkedHashMap;
import java.util.Map;

/** Whether this gateway can usefully take traffic right now (served by {@code /readyz}). */
public record Readiness(boolean ready, String reason, String leader, int backends, int healthy) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ready", ready);
        if (reason != null && !reason.isEmpty()) {
            m.put("reason", reason);
        }
        m.put("leader", leader == null ? "" : leader);
        m.put("backends", backends);
        m.put("healthy", healthy);
        return m;
    }
}
