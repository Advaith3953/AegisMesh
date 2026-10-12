package aegismesh.control;

import aegismesh.lb.Spec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable snapshot of the replicated configuration. */
public record State(long version, Map<String, Spec> backends, Map<String, String> policies) {
    public List<Spec> specs() {
        return new ArrayList<>(new TreeMap<>(backends).values());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> bs = new LinkedHashMap<>();
        new TreeMap<>(backends).forEach((k, v) -> bs.put(k, v.toMap()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("backends", bs);
        m.put("policies", new TreeMap<>(policies));
        return m;
    }
}
