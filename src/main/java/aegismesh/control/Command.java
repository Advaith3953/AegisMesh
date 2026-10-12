package aegismesh.control;

import aegismesh.lb.Spec;
import aegismesh.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A change to the replicated configuration. {@code op} is one of {@code add_backend}, {@code remove_backend},
 * {@code set_policy}. Commands are validated <em>before</em> they enter the log, so a bad request is refused with an
 * error instead of being committed and then silently ignored by every replica.
 */
public record Command(String op, Spec backend, String id, String service, String policy) {
    public static Command addBackend(Spec s) {
        return new Command("add_backend", s, "", "", "");
    }

    public static Command removeBackend(String id) {
        return new Command("remove_backend", null, id, "", "");
    }

    public static Command setPolicy(String service, String policy) {
        return new Command("set_policy", null, "", service, policy);
    }

    /** @throws ControlException (invalid = true) describing the first problem found */
    public void validate() throws ControlException {
        switch (op == null ? "" : op) {
            case "add_backend" -> {
                if (backend == null) {
                    throw bad("add_backend needs a backend");
                }
                if (backend.id().isEmpty() || backend.service().isEmpty() || backend.addr().isEmpty()) {
                    throw bad("backend id, service and addr are all required");
                }
                for (char c : backend.service().toCharArray()) {
                    if (c == '/' || c == '?' || c == '#' || c == '%') {
                        throw bad("service name '" + backend.service() + "' may not contain / ? # %");
                    }
                }
                if (!validHostPort(backend.addr())) {
                    throw bad("backend addr '" + backend.addr() + "' must look like host:port");
                }
                if (backend.weight() < 0) {
                    throw bad("weight must be >= 0 (0 means default)");
                }
            }
            case "remove_backend" -> {
                if (id == null || id.isEmpty()) {
                    throw bad("remove_backend needs an id");
                }
            }
            case "set_policy" -> {
                if (service == null || service.isEmpty()) {
                    throw bad("set_policy needs a service");
                }
                if (!"adaptive".equals(policy) && !"roundrobin".equals(policy)) {
                    throw bad("policy must be \"adaptive\" or \"roundrobin\", got \"" + policy + "\"");
                }
            }
            default -> throw bad("unknown op '" + op + "'");
        }
    }

    private static ControlException bad(String msg) {
        return new ControlException("invalid command: " + msg, true);
    }

    static boolean validHostPort(String a) {
        int i = a.lastIndexOf(':');
        if (i <= 0 || i == a.length() - 1) {
            return false;
        }
        String host = a.substring(0, i);
        if (host.startsWith("[")) {
            if (!host.endsWith("]") || host.length() < 3) {
                return false;
            }
        } else if (host.indexOf(':') >= 0) {
            return false; // an unbracketed IPv6 address is ambiguous
        }
        for (char c : host.toCharArray()) {
            if (Character.isWhitespace(c) || c == '/' || c == '?' || c == '#') {
                return false;
            }
        }
        try {
            int port = Integer.parseInt(a.substring(i + 1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        if (backend != null) {
            m.put("backend", backend.toMap());
        }
        if (id != null && !id.isEmpty()) {
            m.put("id", id);
        }
        if (service != null && !service.isEmpty()) {
            m.put("service", service);
        }
        if (policy != null && !policy.isEmpty()) {
            m.put("policy", policy);
        }
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    /** @throws Json.JsonException for malformed JSON or fields of the wrong type */
    public static Command fromJson(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        Object b = m.get("backend");
        return new Command(Json.str(m, "op", ""), b == null ? null : Spec.fromMap(Json.obj(b)),
                Json.str(m, "id", ""), Json.str(m, "service", ""), Json.str(m, "policy", ""));
    }
}
