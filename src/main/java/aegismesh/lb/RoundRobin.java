package aegismesh.lb;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Cycles through the candidates, ignoring all signals (the baseline). */
public final class RoundRobin implements Policy {
    private final AtomicLong n = new AtomicLong();

    @Override
    public String name() {
        return "roundrobin";
    }

    @Override
    public Backend pick(List<Backend> c) {
        if (c.isEmpty()) {
            return null;
        }
        return c.get((int) Math.floorMod(n.getAndIncrement(), (long) c.size()));
    }
}
