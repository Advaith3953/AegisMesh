package aegismesh.raft;

import aegismesh.raft.Types.AppendEntriesArgs;
import aegismesh.raft.Types.AppendEntriesReply;
import aegismesh.raft.Types.RequestVoteArgs;
import aegismesh.raft.Types.RequestVoteReply;
import aegismesh.raft.Types.Transport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/** Raft RPCs as JSON over HTTP: {@code POST /raft/vote} and {@code POST /raft/append}. */
public final class HttpTransport implements Transport {
    private final Map<String, String> addrs;
    private final HttpClient client;

    public HttpTransport(Map<String, String> addrs) {
        this.addrs = addrs;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    }

    private String post(String to, String path, String body, Duration timeout) throws IOException {
        String addr = addrs.get(to);
        if (addr == null) {
            throw new IOException("raft: unknown peer '" + to + "'");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + addr + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("raft: " + path + " returned HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public RequestVoteReply requestVote(String to, RequestVoteArgs a, Duration timeout) throws IOException {
        try {
            return Codec.voteReply(post(to, "/raft/vote", Codec.encode(a), timeout));
        } catch (RuntimeException e) {
            throw new IOException("raft: bad reply from " + to + ": " + e.getMessage(), e);
        }
    }

    @Override
    public AppendEntriesReply appendEntries(String to, AppendEntriesArgs a, Duration timeout) throws IOException {
        try {
            return Codec.appendReply(post(to, "/raft/append", Codec.encode(a), timeout));
        } catch (RuntimeException e) {
            throw new IOException("raft: bad reply from " + to + ": " + e.getMessage(), e);
        }
    }
}
