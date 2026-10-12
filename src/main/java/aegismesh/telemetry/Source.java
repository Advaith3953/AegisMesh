package aegismesh.telemetry;

import java.io.IOException;
import java.util.Map;

/** Produces per-backend kernel signals keyed by resolved {@code ip:port}. */
public interface Source extends AutoCloseable {
    String name();

    Map<String, Signal> sample() throws IOException;

    @Override
    void close();
}
