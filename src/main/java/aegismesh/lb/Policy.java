package aegismesh.lb;

import java.util.List;

/** Chooses one backend among the candidates the router has already filtered. */
public interface Policy {
    String name();

    /** @return the chosen backend, or {@code null} when {@code candidates} is empty */
    Backend pick(List<Backend> candidates);

    static Policy create(String name, ScoreConfig cfg) {
        return "adaptive".equals(name) ? new Adaptive(cfg) : new RoundRobin();
    }
}
