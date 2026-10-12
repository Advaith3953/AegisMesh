package aegismesh.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchTest {
    @Test
    void percentilesUseTheNearestRankMethod() {
        List<Double> v = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        assertEquals(5.0, Bench.percentile(v, 50));
        assertEquals(10.0, Bench.percentile(v, 99));
        assertEquals(1.0, Bench.percentile(v, 0));
        assertEquals(0.0, Bench.percentile(List.of(), 99));
        assertEquals(7.0, Bench.percentile(List.of(7.0), 50));
    }

    @Test
    void statsSeparateErrorsFromLatenciesAndComputeTrafficShares() {
        List<Bench.Sample> s = List.of(new Bench.Sample(0, 10, true, "w1"), new Bench.Sample(1, 20, true, "w2"),
                new Bench.Sample(2, 30, true, "w2"), new Bench.Sample(3, 99, false, ""), new Bench.Sample(4, 40, true, "w2"));
        Bench.Stats st = Bench.stats(s, 2.0);
        assertEquals(5, st.total());
        assertEquals(1, st.errors());
        assertEquals(20.0, st.errPct(), 1e-9);
        assertEquals(2.0, st.rps(), 1e-9, "4 successful requests over 2 seconds");
        assertEquals(20.0, st.p50(), 1e-9, "errors are excluded from latency percentiles");
        assertEquals(25.0, st.share().get("w1"), 1e-9);
        assertEquals(75.0, st.share().get("w2"), 1e-9);
        assertEquals(0.0, Bench.stats(List.of(), 1).errPct());
        assertEquals(0.0, Bench.stats(s, 0).rps());
    }

    @Test
    void reportsAreWellFormed() {
        Bench.Stats st = Bench.stats(List.of(new Bench.Sample(0, 10, true, "w1"), new Bench.Sample(1, 12, true, "w2")), 1);
        List<Bench.Run> runs = List.of(new Bench.Run("normal", "rr-naive", 0, st, st, ""),
                new Bench.Run("normal", "rr-naive", 1, st, st, ""), new Bench.Run("normal", "aegis-adaptive", 0, st, st, ""));
        String csv = Bench.csv(runs);
        String[] lines = csv.trim().split("\n");
        assertEquals(3, lines.length, "header plus one aggregated row per (scenario, arm)");
        assertTrue(lines[1].startsWith("normal,rr-naive,2,"));
        String md = Bench.markdown(runs, 1.0, 400);
        assertTrue(md.contains("## normal") && md.contains("| aegis-adaptive |"));
        Map<String, Object> m = Bench.toMap(runs.get(0));
        assertEquals("normal", m.get("scenario"));
        assertEquals(Json.write(m), Json.write(Json.parse(Json.write(m))));
    }

    @Test
    void freePortsAreUsable() throws Exception {
        assertTrue(Bench.freePort() > 0);
    }

    /** The headline claim, measured: with one backend crashing, resilience removes (nearly) all client-visible errors. */
    @Test
    void resilienceCutsErrorsWhenABackendCrashes(@TempDir Path out) throws Exception {
        Bench.main(new String[] {"-scenarios", "backend_failure", "-arms", "rr-naive,rr-resilient", "-reps", "1",
                "-scale", "0.4", "-rps", "150", "-out", out.toString()});
        List<Object> runs = Json.arr(Json.parse(Files.readString(out.resolve("results.json"))));
        assertEquals(2, runs.size());
        double naive = -1;
        double resilient = -1;
        for (Object o : runs) {
            Map<String, Object> m = Json.obj(o);
            Map<String, Object> overall = Json.obj(m.get("overall"));
            double errPct = 100.0 * Json.lng(overall, "errors", 0) / Math.max(1, Json.lng(overall, "requests", 1));
            if ("rr-naive".equals(m.get("arm"))) {
                naive = errPct;
            } else {
                resilient = errPct;
            }
        }
        assertTrue(naive > 5, "round-robin without resilience keeps sending a quarter of the traffic to a dead backend: " + naive + "%");
        assertTrue(resilient < naive / 2, "resilient arm should have far fewer errors (" + resilient + "% vs " + naive + "%)");
        assertTrue(Files.readString(out.resolve("summary.md")).contains("backend_failure"));
        assertTrue(Files.readString(out.resolve("summary.csv")).startsWith("scenario,arm,reps"));
    }

    @Test
    void theClusterScenarioSurvivesALeaderKill(@TempDir Path out) throws Exception {
        Bench.main(new String[] {"-scenarios", "leader_failure", "-arms", "aegis-adaptive", "-reps", "1", "-scale", "0.4",
                "-rps", "100", "-out", out.toString()});
        Map<String, Object> run = Json.obj(Json.arr(Json.parse(Files.readString(out.resolve("results.json")))).get(0));
        Map<String, Object> overall = Json.obj(run.get("overall"));
        long total = Json.lng(overall, "requests", 0);
        long errors = Json.lng(overall, "errors", 0);
        assertTrue(total > 100, "requests were issued: " + total);
        assertTrue(errors * 20 < total, "a leader failure must not break the data plane (errors " + errors + " of " + total + ")");
    }
}
