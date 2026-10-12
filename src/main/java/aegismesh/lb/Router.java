package aegismesh.lb;

import aegismesh.breaker.Breaker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pools of backends per service plus the routing policy of each pool. {@link #reconcile} makes the live pools match
 * a desired set of {@link Spec}s while keeping the learned signals (latency, errors, breaker state) of backends
 * that did not change.
 */
public final class Router {
    private static final class Pool {
        final List<Backend> backends = new ArrayList<>();
        Policy policy;
        String policyName;
    }

    public final ScoreConfig score;
    public final Breaker.Config breakerCfg;
    public final EventLog events;
    private final String defaultPolicy;

    private Map<String, Pool> pools = new HashMap<>();
    private Map<String, String> policies = new HashMap<>();

    public Router(ScoreConfig score, Breaker.Config breakerCfg, EventLog events, String defaultPolicy) {
        this.score = score;
        this.breakerCfg = breakerCfg;
        this.events = events;
        this.defaultPolicy = defaultPolicy;
    }

    private Backend newBackend(Spec s) {
        Backend b = new Backend(s.id(), s.service(), s.addr(), breakerCfg);
        b.setWeight(s.weight());
        String id = s.id();
        b.breaker.onChange((from, to) -> events.emit("breaker", id, from + "->" + to));
        return b;
    }

    private String policyFor(String service) {
        return policies.getOrDefault(service, defaultPolicy);
    }

    /** Replaces the live pools so they match {@code specs}. */
    public synchronized void reconcile(List<Spec> specs) {
        Map<String, Backend> existing = new HashMap<>();
        for (Pool p : pools.values()) {
            for (Backend b : p.backends) {
                existing.put(b.id, b);
            }
        }
        Map<String, Pool> next = new HashMap<>();
        for (Spec s : specs) {
            Backend b = existing.get(s.id());
            if (b == null || !b.addr.equals(s.addr()) || !b.service.equals(s.service())) {
                b = newBackend(s);
            } else {
                b.setWeight(s.weight()); // desired state wins: an omitted weight goes back to the default
            }
            Pool p = next.get(s.service());
            if (p == null) {
                String name = policyFor(s.service());
                p = new Pool();
                p.policy = Policy.create(name, score);
                p.policyName = name;
                next.put(s.service(), p);
            }
            p.backends.add(b);
        }
        for (Pool p : next.values()) {
            p.backends.sort(Comparator.comparing(x -> x.id));
        }
        pools = next;
    }

    /** Sets the per-service policy overrides; pools whose effective policy changed get a fresh policy object. */
    public synchronized void setPolicies(Map<String, String> m) {
        policies = new HashMap<>(m);
        for (Map.Entry<String, Pool> e : pools.entrySet()) {
            String want = policyFor(e.getKey());
            if (!want.equals(e.getValue().policyName)) {
                e.getValue().policy = Policy.create(want, score);
                e.getValue().policyName = want;
            }
        }
    }

    /** Result of {@link #pick}. */
    public record Pick(Backend backend, int candidates) {}

    /**
     * Chooses a backend for {@code service}. With {@code resilient}, ejected backends and backends whose breaker
     * refuses are filtered out first. {@code exclude} lists backends already tried for this request.
     */
    public Pick pick(String service, boolean resilient, Set<Backend> exclude) {
        Policy policy;
        List<Backend> cands;
        synchronized (this) {
            Pool p = pools.get(service);
            if (p == null) {
                return new Pick(null, 0);
            }
            cands = new ArrayList<>(p.backends.size());
            for (Backend b : p.backends) {
                if (exclude != null && exclude.contains(b)) {
                    continue;
                }
                if (resilient && !(b.healthy() && b.breaker.admits())) {
                    continue;
                }
                cands.add(b);
            }
            policy = p.policy;
        }
        return new Pick(policy.pick(cands), cands.size());
    }

    /** Every backend of every service, sorted by id. */
    public synchronized List<Backend> all() {
        List<Backend> out = new ArrayList<>();
        for (Pool p : pools.values()) {
            out.addAll(p.backends);
        }
        out.sort(Comparator.comparing(x -> x.id));
        return out;
    }

    /** service → effective policy name. */
    public synchronized Map<String, String> services() {
        Map<String, String> m = new TreeMap<>();
        for (Map.Entry<String, Pool> e : pools.entrySet()) {
            m.put(e.getKey(), e.getValue().policyName);
        }
        return Collections.unmodifiableMap(m);
    }
}
