package aegismesh.lb;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks the lowest-cost backend. Up to 8 candidates are compared exactly (starting at a random offset so ties do
 * not always go to the same backend); with more, "power of two choices" samples two at random and takes the
 * cheaper, which keeps the per-request work constant.
 */
public final class Adaptive implements Policy {
    private final ScoreConfig cfg;

    public Adaptive(ScoreConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "adaptive";
    }

    @Override
    public Backend pick(List<Backend> c) {
        if (c.isEmpty()) {
            return null;
        }
        if (c.size() == 1) {
            return c.get(0);
        }
        long now = System.nanoTime();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (c.size() > 8) {
            int i = rng.nextInt(c.size());
            int j = rng.nextInt(c.size() - 1);
            if (j >= i) {
                j++;
            }
            return c.get(i).cost(cfg, now) <= c.get(j).cost(cfg, now) ? c.get(i) : c.get(j);
        }
        int off = rng.nextInt(c.size());
        Backend best = null;
        double bestCost = 0;
        for (int k = 0; k < c.size(); k++) {
            Backend b = c.get((k + off) % c.size());
            double cost = b.cost(cfg, now);
            if (best == null || cost < bestCost) {
                best = b;
                bestCost = cost;
            }
        }
        return best;
    }
}
