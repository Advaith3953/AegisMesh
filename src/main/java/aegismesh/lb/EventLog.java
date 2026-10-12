package aegismesh.lb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Append-only log of operational events (ejections, re-introductions, breaker transitions). */
public final class EventLog {
    public record Event(Instant at, String kind, String backend, String detail) {}

    private final List<Event> events = new ArrayList<>();

    public synchronized void emit(String kind, String backend, String detail) {
        events.add(new Event(Instant.now(), kind, backend, detail));
    }

    public synchronized List<Event> all() {
        return new ArrayList<>(events);
    }
}
