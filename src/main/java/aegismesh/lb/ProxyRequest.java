package aegismesh.lb;

import java.util.List;
import java.util.Map;

/** A client request as the gateway sees it; {@code rawPath} keeps percent-encoding exactly as the client sent it. */
public record ProxyRequest(String method, String rawPath, String rawQuery, Map<String, List<String>> headers,
                           byte[] body) {}
