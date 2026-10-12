package aegismesh.lb;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Where the gateway delivers its answer; throws {@link IOException} when the client has gone away. */
@FunctionalInterface
public interface Responder {
    void send(int status, Map<String, List<String>> headers, byte[] body) throws IOException;
}
