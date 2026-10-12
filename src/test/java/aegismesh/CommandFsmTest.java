package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.control.Command;
import aegismesh.control.ControlException;
import aegismesh.control.Fsm;
import aegismesh.control.State;
import aegismesh.lb.Spec;
import aegismesh.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandFsmTest {
    private static Spec spec(String id, String svc, String addr, int w) {
        return new Spec(id, svc, addr, w);
    }

    private static String reason(Command c) {
        ControlException e = assertThrows(ControlException.class, c::validate);
        assertTrue(e.invalid(), "validation errors are client errors (400)");
        return e.getMessage();
    }

    @Test
    void acceptsEveryDocumentedCommand() throws Exception {
        List<Command> ok = List.of(
                Command.addBackend(spec("a", "api", "127.0.0.1:80", 0)),
                Command.addBackend(spec("a", "api", "backend.internal:8080", 5)),
                Command.addBackend(spec("a", "api", "[::1]:80", 1)),
                Command.removeBackend("a"),
                Command.setPolicy("api", "adaptive"),
                Command.setPolicy("api", "roundrobin"));
        for (Command c : ok) {
            c.validate();
        }
    }

    @Test
    void rejectsEveryKindOfBadBackend() {
        assertTrue(reason(Command.addBackend(null)).contains("needs a backend"));
        assertTrue(reason(Command.addBackend(spec("", "api", "h:1", 0))).contains("required"));
        assertTrue(reason(Command.addBackend(spec("a", "", "h:1", 0))).contains("required"));
        assertTrue(reason(Command.addBackend(spec("a", "api", "", 0))).contains("required"));
        for (String bad : new String[] {"localhost", "h:", ":80", "h:abc", "h:0", "h:99999", "h:-1", "a:b:80", "h :80", "h/x:80",
                "[::1:80", "[]:80", "h:80?x"}) {
            assertTrue(reason(Command.addBackend(spec("a", "api", bad, 0))).contains("host:port"), bad);
        }
        for (String badSvc : new String[] {"a/b", "a?b", "a#b", "a%b"}) {
            assertTrue(reason(Command.addBackend(spec("a", badSvc, "h:1", 0))).contains("service name"), badSvc);
        }
        assertTrue(reason(Command.addBackend(spec("a", "api", "h:1", -2))).contains("weight"));
    }

    @Test
    void rejectsBadPoliciesRemovalsAndUnknownOps() {
        assertTrue(reason(Command.setPolicy("api", "bogus")).contains("policy"));
        assertTrue(reason(Command.setPolicy("api", "")).contains("policy"));
        assertTrue(reason(Command.setPolicy("", "adaptive")).contains("needs a service"));
        assertTrue(reason(Command.removeBackend("")).contains("needs an id"));
        assertTrue(reason(new Command("explode", null, "", "", "")).contains("unknown op"));
        assertTrue(reason(new Command(null, null, "", "", "")).contains("unknown op"));
    }

    @Test
    void roundTripsThroughJson() {
        for (Command c : List.of(Command.addBackend(spec("a", "api", "h:1", 3)), Command.removeBackend("a"),
                Command.setPolicy("api", "roundrobin"))) {
            assertEquals(c, Command.fromJson(c.toJson()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "not json", "{\"op\":5}", "{\"op\":\"add_backend\",\"backend\":3}",
            "{\"op\":\"add_backend\",\"backend\":{\"weight\":\"heavy\"}}"})
    void malformedJsonIsAJsonExceptionNotACrash(String bad) {
        assertThrows(Json.JsonException.class, () -> Command.fromJson(bad));
    }

    @Test
    void theStateMachineAppliesCommandsAndReportsEachChange() {
        List<State> seen = new ArrayList<>();
        Fsm f = new Fsm(seen::add);
        f.apply(1, Command.addBackend(spec("b", "api", "h:2", 0)).toJson());
        f.apply(2, Command.addBackend(spec("a", "api", "h:1", 2)).toJson());
        f.apply(3, Command.setPolicy("api", "roundrobin").toJson());
        f.apply(4, Command.removeBackend("b").toJson());
        f.apply(5, Command.removeBackend("never-existed").toJson()); // legal and idempotent
        State s = f.snapshot();
        assertEquals(5, s.version());
        assertEquals(List.of(spec("a", "api", "h:1", 2)), s.specs());
        assertEquals("roundrobin", s.policies().get("api"));
        assertEquals(5, seen.size());
        assertEquals(1, seen.get(0).version());
        assertEquals(2, seen.get(1).specs().size());
        assertEquals(List.of("a", "b"), seen.get(1).specs().stream().map(Spec::id).toList(), "specs are sorted by id");
    }

    @Test
    void invalidOrGarbageEntriesAreSkippedWithoutChangingState() {
        Fsm f = new Fsm(null);
        f.apply(1, Command.addBackend(spec("a", "api", "h:1", 0)).toJson());
        for (String bad : new String[] {"not json", "{}", "null", "{\"op\":\"set_policy\",\"service\":\"s\",\"policy\":\"x\"}",
                "{\"op\":\"add_backend\",\"backend\":{\"id\":\"b\",\"service\":\"s\",\"addr\":\"nope\"}}", ""}) {
            f.apply(2, bad);
        }
        assertEquals(1, f.snapshot().version());
        assertEquals(1, f.snapshot().backends().size());
    }

    @Test
    void replicasThatSeeTheSameBytesAlwaysEndUpIdentical() {
        Random rnd = new Random(99);
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pool.add(Command.addBackend(spec("b" + i, "s" + (i % 3), "h:" + (i + 1), i % 4)).toJson());
            pool.add(Command.removeBackend("b" + i).toJson());
        }
        pool.add(Command.setPolicy("s0", "roundrobin").toJson());
        pool.add("garbage");
        pool.add("{\"op\":\"nope\"}");
        Fsm a = new Fsm(null);
        Fsm b = new Fsm(null);
        for (int i = 0; i < 3000; i++) {
            String cmd = pool.get(rnd.nextInt(pool.size()));
            if (rnd.nextInt(10) == 0) { // random byte noise too
                StringBuilder sb = new StringBuilder(cmd);
                sb.setCharAt(rnd.nextInt(sb.length()), (char) rnd.nextInt(128));
                cmd = sb.toString();
            }
            a.apply(i, cmd);
            b.apply(i, cmd);
            assertEquals(a.snapshot(), b.snapshot());
        }
    }

    @Test
    void stateSerialisesDeterministically() {
        Fsm f = new Fsm(null);
        f.apply(1, Command.addBackend(spec("z", "api", "h:9", 0)).toJson());
        f.apply(2, Command.addBackend(spec("a", "api", "h:1", 0)).toJson());
        String json = Json.write(f.snapshot().toMap());
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"z\""));
        assertFalse(json.isEmpty());
    }
}
