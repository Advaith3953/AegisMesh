package aegismesh;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Helpers shared by the tests. */
public final class TestUtil {
    private TestUtil() {}

    public static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public record Resp(int status, String body, java.net.http.HttpHeaders headers) {
        public String header(String name) {
            return headers == null ? "" : headers.firstValue(name).orElse("");
        }
    }

    public static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends a request; a connection failure is reported as status -1 with the error text as body. */
    public static Resp http(String method, String url, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body().trim(), r.headers());
        } catch (IOException e) {
            return new Resp(-1, e.toString(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(-1, "interrupted", null);
        }
    }

    public static Resp get(String url) {
        return http("GET", url, null);
    }

    public static Resp post(String url, String body) {
        return http("POST", url, body);
    }

    public static void eventually(String what, Duration within, BooleanSupplier cond) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (cond.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // condition not ready yet
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
