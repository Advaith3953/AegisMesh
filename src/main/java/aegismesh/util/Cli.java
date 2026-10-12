package aegismesh.util;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny command-line flag parser. Accepts {@code -name value}, {@code --name value} and {@code -name=value};
 * boolean flags may be given bare ({@code -resilient}) or as {@code -resilient=false}. Every problem (unknown flag,
 * missing value, malformed number) is reported as a {@link CliException} with a readable message instead of being
 * silently ignored.
 */
public final class Cli {
    /** A usage error; {@link #helpRequested()} is true for {@code -h}/{@code --help}. */
    public static final class CliException extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean help;

        public CliException(String message, boolean help) {
            super(message);
            this.help = help;
        }

        public boolean helpRequested() {
            return help;
        }
    }

    private enum Type { STRING, INT, DOUBLE, BOOL, DURATION }

    private record Flag(String name, Type type, String def, String help) {}

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)");

    private final String program;
    private final Map<String, Flag> flags = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();

    public Cli(String program) {
        this.program = program;
    }

    public Cli string(String name, String def, String help) {
        return add(name, Type.STRING, def, help);
    }

    public Cli integer(String name, int def, String help) {
        return add(name, Type.INT, Integer.toString(def), help);
    }

    public Cli number(String name, double def, String help) {
        return add(name, Type.DOUBLE, Double.toString(def), help);
    }

    public Cli bool(String name, boolean def, String help) {
        return add(name, Type.BOOL, Boolean.toString(def), help);
    }

    public Cli duration(String name, String def, String help) {
        return add(name, Type.DURATION, def, help);
    }

    private Cli add(String name, Type t, String def, String help) {
        flags.put(name, new Flag(name, t, def, help));
        return this;
    }

    public String usage() {
        StringBuilder sb = new StringBuilder("usage: ").append(program).append(" [flags]\n");
        for (Flag f : flags.values()) {
            sb.append(String.format("  -%-18s %s (default %s)%n", f.name, f.help, f.def.isEmpty() ? "\"\"" : f.def));
        }
        return sb.toString();
    }

    public Cli parse(String[] args) throws CliException {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--help") || a.equals("-help")) {
                throw new CliException(usage(), true);
            }
            if (!a.startsWith("-") || a.equals("-") || a.equals("--")) {
                throw new CliException("unexpected argument '" + a + "'", false);
            }
            String body = a.startsWith("--") ? a.substring(2) : a.substring(1);
            String name = body;
            String value = null;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                name = body.substring(0, eq);
                value = body.substring(eq + 1);
            }
            Flag f = flags.get(name);
            if (f == null) {
                throw new CliException("flag provided but not defined: -" + name, false);
            }
            if (value == null) {
                if (f.type == Type.BOOL) {
                    value = "true";
                } else {
                    if (i + 1 >= args.length) {
                        throw new CliException("flag needs a value: -" + name, false);
                    }
                    value = args[++i];
                }
            }
            validate(f, value);
            values.put(name, value);
        }
        return this;
    }

    private static void validate(Flag f, String v) throws CliException {
        try {
            switch (f.type) {
                case INT -> Integer.parseInt(v);
                case DOUBLE -> Double.parseDouble(v);
                case BOOL -> {
                    if (!v.equals("true") && !v.equals("false")) {
                        throw new IllegalArgumentException("not a boolean");
                    }
                }
                case DURATION -> parseDuration(v);
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            throw new CliException("invalid value '" + v + "' for flag -" + f.name + " (expected "
                    + f.type.name().toLowerCase() + ")", false);
        }
    }

    /** Parses {@code 250ms}, {@code 1.5s}, {@code 2m}, {@code 1h}. */
    public static Duration parseDuration(String v) {
        Matcher m = DURATION.matcher(v);
        if (!m.matches()) {
            throw new IllegalArgumentException("bad duration " + v);
        }
        double n = Double.parseDouble(m.group(1));
        double ms = switch (m.group(2)) {
            case "ms" -> n;
            case "s" -> n * 1000;
            case "m" -> n * 60_000;
            default -> n * 3_600_000;
        };
        return Duration.ofNanos((long) (ms * 1_000_000));
    }

    private String raw(String name) {
        Flag f = flags.get(name);
        if (f == null) {
            throw new IllegalArgumentException("undeclared flag " + name);
        }
        return values.getOrDefault(name, f.def);
    }

    public String getString(String name) {
        return raw(name);
    }

    public int getInt(String name) {
        return Integer.parseInt(raw(name));
    }

    public double getNumber(String name) {
        return Double.parseDouble(raw(name));
    }

    public boolean getBool(String name) {
        return Boolean.parseBoolean(raw(name));
    }

    public Duration getDuration(String name) {
        return parseDuration(raw(name));
    }
}
