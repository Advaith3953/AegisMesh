package aegismesh.telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code /proc/net/tcp} and {@code /proc/net/tcp6} (Linux). For every ESTABLISHED connection it sums the
 * outstanding retransmission timeouts and the unsent bytes, keyed by the connection's <em>remote</em> address: the
 * gateway's outbound connections to a backend show that backend's trouble before request latency does.
 */
public final class ProcSource implements Source {
    private final Path tcp;
    private final Path tcp6;

    public ProcSource() {
        this(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"));
    }

    public ProcSource(Path tcp, Path tcp6) {
        this.tcp = tcp;
        this.tcp6 = tcp6;
    }

    @Override
    public String name() {
        return "proc";
    }

    @Override
    public Map<String, Signal> sample() throws IOException {
        Map<String, Signal> out = new HashMap<>();
        parse(Files.readAllLines(tcp), out);
        if (Files.exists(tcp6)) {
            parse(Files.readAllLines(tcp6), out);
        }
        return out;
    }

    /** Parses the lines of one /proc/net/tcp* file into {@code out} (header and malformed lines are skipped). */
    static void parse(List<String> lines, Map<String, Signal> out) {
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 7 || !f[0].endsWith(":") || !"01".equals(f[3])) { // 01 = ESTABLISHED
                continue;
            }
            String remote = decodeAddr(f[2]);
            if (remote == null) {
                continue;
            }
            String[] q = f[4].split(":");
            try {
                long tx = q.length == 2 ? Long.parseLong(q[0], 16) : 0;
                long retrans = Long.parseLong(f[6], 16);
                out.merge(remote, new Signal(retrans, tx), (a, b) -> new Signal(a.retrans() + b.retrans(),
                        a.sendQueueBytes() + b.sendQueueBytes()));
            } catch (NumberFormatException ignored) {
                // skip lines we cannot understand
            }
        }
    }

    /** Decodes {@code 0100007F:1F90} (little-endian IPv4) or an IPv4-mapped / loopback IPv6 address to {@code ip:port}. */
    static String decodeAddr(String s) {
        int c = s.indexOf(':');
        if (c <= 0) {
            return null;
        }
        try {
            int port = Integer.parseInt(s.substring(c + 1), 16);
            String h = s.substring(0, c);
            if (h.length() == 8) {
                return ipv4(h) + ":" + port;
            }
            if (h.length() == 32) {
                if (h.startsWith("0000000000000000FFFF0000")) {
                    return ipv4(h.substring(24)) + ":" + port;
                }
                if (h.equals("00000000000000000000000001000000")) {
                    return "::1:" + port;
                }
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String ipv4(String le) {
        int a = Integer.parseInt(le.substring(6, 8), 16);
        int b = Integer.parseInt(le.substring(4, 6), 16);
        int c = Integer.parseInt(le.substring(2, 4), 16);
        int d = Integer.parseInt(le.substring(0, 2), 16);
        return a + "." + b + "." + c + "." + d;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
