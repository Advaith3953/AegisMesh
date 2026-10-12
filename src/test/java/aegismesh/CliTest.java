package aegismesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.util.Cli;
import aegismesh.util.Cli.CliException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CliTest {
    private static Cli cli() {
        return new Cli("t").string("name", "dflt", "a name").integer("n", 5, "a count").number("x", 1.5, "a number")
                .bool("on", false, "a switch").duration("every", "2s", "an interval");
    }

    @Test
    void defaultsApplyWhenNothingIsGiven() throws Exception {
        Cli c = cli().parse(new String[0]);
        assertEquals("dflt", c.getString("name"));
        assertEquals(5, c.getInt("n"));
        assertEquals(1.5, c.getNumber("x"));
        assertFalse(c.getBool("on"));
        assertEquals(Duration.ofSeconds(2), c.getDuration("every"));
    }

    @Test
    void acceptsAllThreeFlagSpellings() throws Exception {
        Cli c = cli().parse(new String[] {"-name", "a", "--n", "7", "-x=2.5", "-on", "--every=250ms"});
        assertEquals("a", c.getString("name"));
        assertEquals(7, c.getInt("n"));
        assertEquals(2.5, c.getNumber("x"));
        assertTrue(c.getBool("on"));
        assertEquals(Duration.ofMillis(250), c.getDuration("every"));
        assertFalse(cli().parse(new String[] {"-on=false"}).getBool("on"));
    }

    @Test
    void parsesDurations() {
        assertEquals(Duration.ofMillis(1500), Cli.parseDuration("1.5s"));
        assertEquals(Duration.ofMinutes(2), Cli.parseDuration("2m"));
        assertEquals(Duration.ofHours(1), Cli.parseDuration("1h"));
        assertThrows(IllegalArgumentException.class, () -> Cli.parseDuration("5"));
        assertThrows(IllegalArgumentException.class, () -> Cli.parseDuration("-1s"));
        assertThrows(IllegalArgumentException.class, () -> Cli.parseDuration("1d"));
    }

    @Test
    void reportsEveryKindOfMistakeInsteadOfIgnoringIt() {
        for (String[] bad : new String[][] {{"-nope", "1"}, {"-name"}, {"-n", "abc"}, {"-n", "1.5"}, {"-x", "z"},
                {"-on=maybe"}, {"-every", "soon"}, {"stray"}, {"-"}, {"--"}}) {
            assertThrows(CliException.class, () -> cli().parse(bad), String.join(" ", bad));
        }
    }

    @Test
    void helpIsDistinguishedFromErrors() {
        CliException e = assertThrows(CliException.class, () -> cli().parse(new String[] {"-h"}));
        assertTrue(e.helpRequested());
        assertTrue(e.getMessage().contains("-name"));
        assertFalse(assertThrows(CliException.class, () -> cli().parse(new String[] {"-zzz"})).helpRequested());
    }

    @Test
    void askingForAnUndeclaredFlagIsAProgrammingError() throws Exception {
        Cli c = cli().parse(new String[0]);
        assertThrows(IllegalArgumentException.class, () -> c.getString("undeclared"));
    }
}
