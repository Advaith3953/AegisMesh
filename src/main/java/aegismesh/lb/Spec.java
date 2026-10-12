package aegismesh.lb;

import aegismesh.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** Desired state of one backend, as stored in the replicated registry. {@code weight} 0 means "default (1)". */
public record Spec(String id, String service, String addr, int weight) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("service", service);
        m.put("addr", addr);
        m.put("weight", weight);
        return m;
    }

    public static Spec fromMap(Map<String, Object> m) {
        long w = Json.lng(m, "weight", 0);
        if (w < Integer.MIN_VALUE || w > Integer.MAX_VALUE) {
            throw new Json.JsonException("weight out of range");
        }
        return new Spec(Json.str(m, "id", ""), Json.str(m, "service", ""), Json.str(m, "addr", ""), (int) w);
    }
}
