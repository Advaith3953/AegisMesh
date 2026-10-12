package aegismesh.raft;

import aegismesh.raft.Types.AppendEntriesArgs;
import aegismesh.raft.Types.AppendEntriesReply;
import aegismesh.raft.Types.Entry;
import aegismesh.raft.Types.PersistentState;
import aegismesh.raft.Types.RequestVoteArgs;
import aegismesh.raft.Types.RequestVoteReply;
import aegismesh.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON encoding of Raft messages and persistent state. */
final class Codec {
    private Codec() {}

    static Map<String, Object> toMap(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", e.term());
        m.put("index", e.index());
        m.put("cmd", e.cmd()); // null = leader no-op
        return m;
    }

    static Entry entry(Object o) {
        Map<String, Object> m = Json.obj(o);
        return new Entry(Json.lng(m, "term", 0), Json.lng(m, "index", 0), Json.str(m, "cmd", null));
    }

    static List<Object> entries(List<Entry> es) {
        List<Object> out = new ArrayList<>();
        for (Entry e : es) {
            out.add(toMap(e));
        }
        return out;
    }

    static List<Entry> entries(Object o) {
        List<Entry> out = new ArrayList<>();
        if (o != null) {
            for (Object x : Json.arr(o)) {
                out.add(entry(x));
            }
        }
        return out;
    }

    static String encode(RequestVoteArgs a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", a.term());
        m.put("candidate_id", a.candidateId());
        m.put("last_log_index", a.lastLogIndex());
        m.put("last_log_term", a.lastLogTerm());
        return Json.write(m);
    }

    static RequestVoteArgs voteArgs(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        return new RequestVoteArgs(Json.lng(m, "term", 0), Json.str(m, "candidate_id", ""),
                Json.lng(m, "last_log_index", 0), Json.lng(m, "last_log_term", 0));
    }

    static String encode(RequestVoteReply r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", r.term());
        m.put("vote_granted", r.voteGranted());
        return Json.write(m);
    }

    static RequestVoteReply voteReply(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        return new RequestVoteReply(Json.lng(m, "term", 0), Json.bool(m, "vote_granted", false));
    }

    static String encode(AppendEntriesArgs a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", a.term());
        m.put("leader_id", a.leaderId());
        m.put("prev_log_index", a.prevLogIndex());
        m.put("prev_log_term", a.prevLogTerm());
        m.put("entries", entries(a.entries()));
        m.put("leader_commit", a.leaderCommit());
        return Json.write(m);
    }

    static AppendEntriesArgs appendArgs(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        return new AppendEntriesArgs(Json.lng(m, "term", 0), Json.str(m, "leader_id", ""),
                Json.lng(m, "prev_log_index", 0), Json.lng(m, "prev_log_term", 0), entries(m.get("entries")),
                Json.lng(m, "leader_commit", 0));
    }

    static String encode(AppendEntriesReply r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", r.term());
        m.put("success", r.success());
        m.put("conflict_index", r.conflictIndex());
        return Json.write(m);
    }

    static AppendEntriesReply appendReply(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        return new AppendEntriesReply(Json.lng(m, "term", 0), Json.bool(m, "success", false),
                Json.lng(m, "conflict_index", 0));
    }

    static String encode(PersistentState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", s.term());
        m.put("voted_for", s.votedFor());
        m.put("log", entries(s.log()));
        return Json.write(m);
    }

    static PersistentState state(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        return new PersistentState(Json.lng(m, "term", 0), Json.str(m, "voted_for", ""), entries(m.get("log")));
    }
}
