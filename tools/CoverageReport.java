import java.io.File;
import java.io.IOException;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;

/** Prints per-package line coverage from a JaCoCo exec file and fails below a threshold. */
public final class CoverageReport {
    public static void main(String[] args) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(new File(args[0]));
        CoverageBuilder cb = new CoverageBuilder();
        Analyzer an = new Analyzer(loader.getExecutionDataStore(), cb);
        an.analyzeAll(new File(args[1]));
        double min = Double.parseDouble(args[2]);
        java.util.TreeMap<String, int[]> pk = new java.util.TreeMap<>();
        int covered = 0;
        int total = 0;
        for (IClassCoverage c : cb.getClasses()) {
            ICounter lines = c.getLineCounter();
            int[] a = pk.computeIfAbsent(c.getPackageName(), k -> new int[2]);
            a[0] += lines.getCoveredCount();
            a[1] += lines.getTotalCount();
            covered += lines.getCoveredCount();
            total += lines.getTotalCount();
        }
        for (var e : pk.entrySet()) {
            System.out.printf("  %-26s %5.1f%%  (%d/%d lines)%n", e.getKey(), 100.0 * e.getValue()[0] / Math.max(1, e.getValue()[1]),
                    e.getValue()[0], e.getValue()[1]);
        }
        double pct = 100.0 * covered / Math.max(1, total);
        System.out.printf("total line coverage %.1f%% (minimum %.0f%%)%n", pct, min);
        if (pct < min) {
            System.exit(1);
        }
    }
}
