package aegismesh.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Small helpers shared by every HTTP endpoint in the project. */
public final class HttpUtil {
    static {
        // The JDK server writes response headers and body separately and, by default, leaves Nagle's algorithm on.
        // Nagle + the client's delayed ACK then stalls many small responses by ~40 ms each. This must be set before
        // the first HttpServer is created, which is why it lives here (every server in this project is made below).
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private HttpUtil() {}

    /** Creates (not yet started) a server that handles each request on its own virtual thread. */
    public static HttpServer newServer(InetSocketAddress addr) throws IOException {
        HttpServer s = HttpServer.create(addr, 1024);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return s;
    }

    /** Parses {@code host:port} (IPv6 hosts in brackets) into a socket address, or throws IllegalArgumentException. */
    public static InetSocketAddress parseAddr(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0 || i == hostPort.length() - 1) {
            throw new IllegalArgumentException("address must look like host:port, got '" + hostPort + "'");
        }
        String host = hostPort.substring(0, i);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(i + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port in '" + hostPort + "'");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range in '" + hostPort + "'");
        }
        return new InetSocketAddress(host, port);
    }

    public static byte[] readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > maxBytes) {
                    throw new IOException("request body larger than " + maxBytes + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        boolean head = "HEAD".equals(ex.getRequestMethod());
        if (head || body.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    public static void text(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    public static void json(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json", (Json.write(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Replies 405 with an Allow header unless the request method is one of {@code allowed}; returns true if OK. */
    public static boolean requireMethod(HttpExchange ex, String... allowed) throws IOException {
        for (String m : allowed) {
            if (m.equals(ex.getRequestMethod())) {
                return true;
            }
        }
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        text(ex, 405, "method not allowed; use " + String.join(" or ", allowed));
        return false;
    }
}
