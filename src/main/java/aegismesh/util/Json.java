package aegismesh.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON reader/writer (the JDK ships none, and this project has no third-party dependencies).
 *
 * <p>Values map to {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Long} (integers that
 * fit), {@code Double}, {@code Boolean} and {@code null}. Parsing is strict: no comments, no trailing commas, no
 * trailing garbage, and nesting is limited so hostile input cannot overflow the stack.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {}

    /** Thrown for malformed JSON or a value of the wrong type. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("no input");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("unexpected trailing characters");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        JsonException error(String msg) {
            return new JsonException(msg + " at offset " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos);
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            char c = peek();
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("invalid literal");
            }
            pos += word.length();
            return value;
        }

        Map<String, Object> object(int depth) {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String k = string();
                skipWs();
                if (peek() != ':') {
                    throw error("expected ':'");
                }
                pos++;
                skipWs();
                m.put(k, value(depth + 1));
                skipWs();
                char c = peek();
                if (c == '}') {
                    pos++;
                    return m;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
                pos++;
            }
        }

        List<Object> array(int depth) {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                char c = peek();
                if (c == ']') {
                    pos++;
                    return l;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
                pos++;
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw error("control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("short \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> {
                        pos--;
                        throw error("bad escape");
                    }
                }
            }
        }

        boolean digitAt(int i) {
            return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            int digitsStart = pos;
            while (digitAt(pos)) {
                pos++;
            }
            if (pos == digitsStart) {
                throw error("invalid number");
            }
            if (s.charAt(digitsStart) == '0' && pos - digitsStart > 1) {
                throw error("leading zero in number");
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                int f = pos;
                while (digitAt(pos)) {
                    pos++;
                }
                if (pos == f) {
                    throw error("digits required after '.'");
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int f = pos;
                while (digitAt(pos)) {
                    pos++;
                }
                if (pos == f) {
                    throw error("digits required in exponent");
                }
            }
            String t = s.substring(start, pos);
            if (integral) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException ignored) {
                    // too large for a long: fall through to double
                }
            }
            return Double.parseDouble(t);
        }
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("nesting too deep");
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("NaN and Infinity cannot be represented in JSON");
            }
            sb.append(d);
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, l.get(i), depth + 1);
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot serialise " + v.getClass().getName());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ typed access

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map<?, ?>) {
            return (Map<String, Object>) o;
        }
        throw new JsonException("expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (o instanceof List<?>) {
            return (List<Object>) o;
        }
        throw new JsonException("expected a JSON array");
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new JsonException("field '" + k + "' must be a string");
    }

    public static long lng(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 9e15) {
            return d.longValue();
        }
        throw new JsonException("field '" + k + "' must be an integer");
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new JsonException("field '" + k + "' must be a number");
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new JsonException("field '" + k + "' must be true or false");
    }
}
