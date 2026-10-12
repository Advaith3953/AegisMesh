package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.util.Json;
import aegismesh.util.Json.JsonException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
    @Test
    void parsesEveryValueType() {
        Map<String, Object> m = Json.obj(Json.parse(
                " {\"s\":\"a\\n\\u00e9\\\"\",\"i\":-12,\"d\":1.5e2,\"t\":true,\"f\":false,\"n\":null,\"a\":[1,[2],{}],\"o\":{\"k\":\"v\"}} "));
        assertEquals("a\né\"", m.get("s"));
        assertEquals(-12L, m.get("i"));
        assertEquals(150.0, m.get("d"));
        assertEquals(true, m.get("t"));
        assertEquals(false, m.get("f"));
        assertNull(m.get("n"));
        assertEquals(3, Json.arr(m.get("a")).size());
        assertEquals("v", Json.obj(m.get("o")).get("k"));
    }

    @Test
    void writesAndRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "quote\" back\\ nl\n tab\t ctl\u0001 uni\u00e9");
        m.put("n", 42);
        m.put("l", 9_000_000_000L);
        m.put("d", 0.25);
        m.put("b", true);
        m.put("z", null);
        m.put("list", List.of(1, "x", List.of()));
        Object back = Json.parse(Json.write(m));
        assertEquals(m.get("text"), Json.obj(back).get("text"));
        assertEquals(9_000_000_000L, Json.obj(back).get("l"));
        assertEquals(Json.write(m), Json.write(back));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "[", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[1 2]", "{'a':1}", "tru", "nul",
            "01", "-", "1.", "1e", ".5", "+1", "\"abc", "\"a\\x\"", "\"\\u12\"", "\"\\uZZZZ\"", "\"line\nbreak\"",
            "{} x", "[]]", "1 2", "// c\n1", "NaN", "Infinity", "{\"a\":1 \"b\":2}"})
    void rejectsMalformedInput(String bad) {
        assertThrows(JsonException.class, () -> Json.parse(bad), "should reject: " + bad);
    }

    @Test
    void limitsNestingDepthInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        assertThrows(JsonException.class, () -> Json.parse(deep));
        List<Object> l = new ArrayList<>();
        List<Object> cur = l;
        for (int i = 0; i < 200; i++) {
            List<Object> next = new ArrayList<>();
            cur.add(next);
            cur = next;
        }
        assertThrows(JsonException.class, () -> Json.write(l));
    }

    @Test
    void hugeIntegersFallBackToDouble() {
        assertTrue(Json.parse("123456789012345678901234567890") instanceof Double);
    }

    @Test
    void refusesToWriteNonFiniteOrUnknownTypes() {
        assertThrows(JsonException.class, () -> Json.write(Double.NaN));
        assertThrows(JsonException.class, () -> Json.write(Double.POSITIVE_INFINITY));
        assertThrows(JsonException.class, () -> Json.write(new Object()));
    }

    @Test
    void typedGettersValidateTypes() {
        Map<String, Object> m = Json.obj(Json.parse("{\"s\":\"x\",\"i\":3,\"d\":2.0,\"f\":1.5,\"b\":true}"));
        assertEquals("x", Json.str(m, "s", ""));
        assertEquals("def", Json.str(m, "missing", "def"));
        assertEquals(3, Json.lng(m, "i", 0));
        assertEquals(2, Json.lng(m, "d", 0)); // 2.0 is an integer
        assertEquals(1.5, Json.dbl(m, "f", 0));
        assertTrue(Json.bool(m, "b", false));
        assertThrows(JsonException.class, () -> Json.str(m, "i", ""));
        assertThrows(JsonException.class, () -> Json.lng(m, "f", 0));
        assertThrows(JsonException.class, () -> Json.lng(m, "s", 0));
        assertThrows(JsonException.class, () -> Json.dbl(m, "s", 0));
        assertThrows(JsonException.class, () -> Json.bool(m, "s", false));
        assertThrows(JsonException.class, () -> Json.obj("x"));
        assertThrows(JsonException.class, () -> Json.arr("x"));
        assertThrows(JsonException.class, () -> Json.parse(null));
    }

    /** Whatever garbage goes in, the only thing that may come out is a JsonException. */
    @Test
    void randomInputNeverCrashesTheParser() {
        Random rnd = new Random(42);
        String alphabet = "{}[]\",:\\ntfu0123456789.-+eE \t\nabc\u00e9";
        for (int i = 0; i < 20_000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(40);
            for (int k = 0; k < len; k++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            try {
                Json.parse(sb.toString());
            } catch (JsonException expected) {
                // fine
            }
        }
    }

    @Test
    void randomValuesRoundTrip() {
        Random rnd = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            Object v = randomValue(rnd, 0);
            String once = Json.write(v);
            assertEquals(once, Json.write(Json.parse(once)));
        }
    }

    private static Object randomValue(Random r, int depth) {
        int k = r.nextInt(depth > 3 ? 4 : 6);
        return switch (k) {
            case 0 -> r.nextLong();
            case 1 -> randomString(r);
            case 2 -> r.nextBoolean();
            case 3 -> null;
            case 4 -> {
                List<Object> l = new ArrayList<>();
                for (int i = r.nextInt(4); i > 0; i--) {
                    l.add(randomValue(r, depth + 1));
                }
                yield l;
            }
            default -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = r.nextInt(4); i > 0; i--) {
                    m.put(randomString(r), randomValue(r, depth + 1));
                }
                yield m;
            }
        };
    }

    private static String randomString(Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = r.nextInt(8); i > 0; i--) {
            sb.append((char) (r.nextInt(3) == 0 ? r.nextInt(0x20) : 0x20 + r.nextInt(0x2000)));
        }
        return sb.toString();
    }
}
