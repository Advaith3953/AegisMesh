package aegismesh.raft;

import aegismesh.util.Json;

/** Server side of {@link HttpTransport}: decodes an RPC, runs it on the node, encodes the reply. */
public final class RaftHttp {
    private RaftHttp() {}

    /** @return the JSON reply, or {@code null} if {@code path} is not a Raft RPC path
     *  @throws Json.JsonException for a malformed request body */
    public static String handle(Node node, String path, String body) {
        return switch (path) {
            case "/raft/vote" -> Codec.encode(node.handleRequestVote(Codec.voteArgs(body)));
            case "/raft/append" -> Codec.encode(node.handleAppendEntries(Codec.appendArgs(body)));
            default -> null;
        };
    }
}
