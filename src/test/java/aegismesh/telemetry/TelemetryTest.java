package aegismesh.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.breaker.Breaker;
import aegismesh.lb.Backend;
import aegismesh.lb.EventLog;
import aegismesh.lb.Router;
import aegismesh.lb.ScoreConfig;
import aegismesh.lb.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryTest {
    private static final String HEADER = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";
    // 127.0.0.1:8080 -> 127.0.0.1:42436 ESTABLISHED, 64 KiB unsent, 3 retransmission timeouts
    private static final String ESTAB = "   0: 0100007F:1F90 0100007F:A5C4 01 00010000:00000000 00:00000000 00000003  1000        0 12345 1 0000";
    private static final String LISTEN = "   1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 999 1 0000";

    private static Router router(String... addrs) {
        Router r = new Router(ScoreConfig.defaults(), Breaker.Config.defaults(), new EventLog(), "adaptive");
        java.util.ArrayList<Spec> specs = new java.util.ArrayList<>();
        for (int i = 0; i < addrs.length; i++) {
            specs.add(new Spec("b" + i, "svc", addrs[i], 0));
        }
        r.reconcile(specs);
        return r;
    }

    private static Backend get(Router r, String id) {
        return r.all().stream().filter(b -> b.id.equals(id)).findFirst().orElseThrow();
    }

    @Test
    void decodesLittleEndianIpv4AndMappedAndLoopbackIpv6() {
        assertEquals("127.0.0.1:8080", ProcSource.decodeAddr("0100007F:1F90"));
        assertEquals("10.1.2.3:80", ProcSource.decodeAddr("0302010A:0050"));
        assertEquals("1.2.3.4:443", ProcSource.decodeAddr("0000000000000000FFFF000004030201:01BB"));
        assertEquals("::1:80", ProcSource.decodeAddr("00000000000000000000000001000000:0050"));
        assertNull(ProcSource.decodeAddr("20010DB8000000000000000000000001:0050"), "other IPv6 addresses are skipped");
        assertNull(ProcSource.decodeAddr("garbage"));
        assertNull(ProcSource.decodeAddr("ZZZZZZZZ:0050"));
        assertNull(ProcSource.decodeAddr("0100007F:ZZ"));
    }

    @Test
    void parsesOnlyEstablishedConnectionsKeyedByRemoteAddress() {
        Map<String, Signal> out = new HashMap<>();
        ProcSource.parse(List.of(HEADER, ESTAB, LISTEN, "", "garbage line", "   2: bad"), out);
        assertEquals(Map.of("127.0.0.1:42436", new Signal(3, 65536)), out);
    }

    @Test
    void sumsSeveralConnectionsToTheSameBackend() {
        Map<String, Signal> out = new HashMap<>();
        ProcSource.parse(List.of(ESTAB, ESTAB.replace("12345", "12346")), out);
        assertEquals(new Signal(6, 131072), out.get("127.0.0.1:42436"));
    }

    @Test
    void readsFilesAndToleratesAMissingTcp6(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path tcp = dir.resolve("tcp");
        Files.write(tcp, List.of(HEADER, ESTAB));
        ProcSource s = new ProcSource(tcp, dir.resolve("absent6"));
        assertEquals("proc", s.name());
        assertEquals(1, s.sample().size());
        s.close();
    }

    @Test
    void theRealProcFilesAreReadableOnLinux() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("/proc/net/tcp")));
        assertNotNull(new ProcSource().sample());
    }

    @Test
    void penaltyGrowsWithRetransmissionsAndQueuedBytesButIsCapped() {
        assertEquals(0, Feeder.penalty(new Signal(0, 0)));
        assertEquals(1.5, Feeder.penalty(new Signal(3, 0)), 1e-9);
        assertEquals(1.0, Feeder.penalty(new Signal(0, 65536)), 1e-9);
        assertEquals(5.0, Feeder.penalty(new Signal(0, 100_000_000)), 1e-9);
    }

    private static final class Stub implements Source {
        Map<String, Signal> sig = new HashMap<>();
        IOException err;
        boolean closed;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Map<String, Signal> sample() throws IOException {
            if (err != null) {
                throw err;
            }
            return sig;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void tickPenalisesOnlyBackendsWithTroubleAndClearsItWhenItGoesAway() {
        Router r = router("127.0.0.1:1111", "127.0.0.1:2222");
        Stub src = new Stub();
        Feeder f = new Feeder(src, r, Duration.ofHours(1));
        src.sig.put("127.0.0.1:1111", new Signal(4, 0));
        f.tick();
        long now = System.nanoTime();
        assertEquals(2.0, get(r, "b0").snapshot(r.score, now).kernelPen(), 1e-9);
        assertEquals(0.0, get(r, "b1").snapshot(r.score, now).kernelPen(), 1e-9);
        src.sig.clear();
        f.tick();
        assertEquals(0.0, get(r, "b0").snapshot(r.score, System.nanoTime()).kernelPen(), 1e-9);
        assertEquals(2, f.samples());
    }

    @Test
    void aFailingSourceIsRememberedAndRecoveryClearsIt() {
        Router r = router("127.0.0.1:1111");
        Stub src = new Stub();
        src.err = new IOException("proc unreadable");
        Feeder f = new Feeder(src, r, Duration.ofHours(1));
        f.tick();
        assertNotNull(f.lastError());
        assertEquals(0, f.samples());
        src.err = null;
        f.tick();
        assertNull(f.lastError());
        assertEquals(1, f.samples());
    }

    @Test
    void backgroundLoopSamplesRepeatedlyAndStopsCleanly() throws Exception {
        Router r = router("127.0.0.1:1111");
        Stub src = new Stub();
        Feeder f = new Feeder(src, r, Duration.ofMillis(20));
        f.start();
        f.start(); // idempotent
        aegismesh.TestUtil.eventually("3 samples", Duration.ofSeconds(3), () -> f.samples() >= 3);
        f.stop();
        long n = f.samples();
        Thread.sleep(100);
        assertEquals(n, f.samples(), "no sampling after stop");
        assertTrue(src.closed);
        f.stop(); // idempotent
    }

    @Test
    void resolveTurnsHostnamesIntoIpsAndLeavesUnknownThingsAlone() {
        assertEquals("127.0.0.1:80", Feeder.resolve("localhost:80").replace("0:0:0:0:0:0:0:1", "127.0.0.1"));
        assertEquals("127.0.0.1:80", Feeder.resolve("127.0.0.1:80"));
        assertEquals("noport", Feeder.resolve("noport"));
        assertEquals("no-such-host.invalid:80", Feeder.resolve("no-such-host.invalid:80"));
    }
}
