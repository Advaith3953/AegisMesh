package aegismesh.lb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aegismesh.TestUtil;
import aegismesh.breaker.Breaker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyRouterTest {
    private static final ScoreConfig CFG = ScoreConfig.defaults();

    private static Backend be(String id) {
        return new Backend(id, "svc", "127.0.0.1:1", Breaker.Config.defaults());
    }

    private static Router router(String policy) {
        return new Router(CFG, Breaker.Config.defaults(), new EventLog(), policy);
    }

    private static Spec spec(String id, String svc, String addr) {
        return new Spec(id, svc, addr, 0);
    }

    // ---------------------------------------------------------------- policies

    @Test
    void roundRobinCyclesAndHandlesEmpty() {
        Policy rr = new RoundRobin();
        assertNull(rr.pick(List.of()));
        List<Backend> bs = List.of(be("a"), be("b"), be("c"));
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            seen.add(rr.pick(bs).id);
        }
        assertEquals(List.of("a", "b", "c", "a", "b", "c"), seen);
        assertEquals("roundrobin", rr.name());
    }

    @Test
    void adaptivePicksTheCheapestBackendEveryTime() {
        Policy p = new Adaptive(CFG);
        Backend slow = be("slow");
        Backend fast = be("fast");
        slow.observe(CFG, 200_000_000L, true, System.nanoTime());
        fast.observe(CFG, 5_000_000L, true, System.nanoTime());
        for (int i = 0; i < 200; i++) {
            assertSame(fast, p.pick(List.of(slow, fast)));
        }
        assertNull(p.pick(List.of()));
        assertSame(slow, p.pick(List.of(slow)));
        assertEquals("adaptive", p.name());
    }

    @Test
    void adaptiveSpreadsTiesInsteadOfAlwaysChoosingTheFirst() {
        Policy p = new Adaptive(CFG);
        List<Backend> bs = List.of(be("a"), be("b"), be("c"), be("d"));
        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 4000; i++) {
            hits.merge(p.pick(bs).id, 1, Integer::sum);
        }
        for (String id : List.of("a", "b", "c", "d")) {
            assertTrue(hits.getOrDefault(id, 0) > 600, id + " got " + hits);
        }
    }

    @Test
    void withManyBackendsItSamplesTwoAndNeverPicksTheWorstOfThePair() {
        Policy p = new Adaptive(CFG);
        List<Backend> bs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Backend b = be("b" + i);
            b.observe(CFG, (1 + i) * 1_000_000L, true, System.nanoTime());
            bs.add(b);
        }
        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            hits.merge(p.pick(bs).id, 1, Integer::sum);
        }
        assertNull(hits.get("b19"), "the single worst backend can never win a pairwise comparison");
        assertTrue(hits.getOrDefault("b0", 0) > hits.getOrDefault("b10", 0));
    }

    @Test
    void policyFactoryFallsBackToRoundRobin() {
        assertTrue(Policy.create("adaptive", CFG) instanceof Adaptive);
        assertTrue(Policy.create("roundrobin", CFG) instanceof RoundRobin);
        assertTrue(Policy.create("whatever", CFG) instanceof RoundRobin);
    }

    // ---------------------------------------------------------------- router

    @Test
    void reconcileBuildsPoolsPerService() {
        Router r = router("roundrobin");
        r.reconcile(List.of(spec("a", "api", "h:1"), spec("b", "api", "h:2"), spec("c", "web", "h:3")));
        assertEquals(3, r.all().size());
        assertEquals(Map.of("api", "roundrobin", "web", "roundrobin"), r.services());
        assertNull(r.pick("nope", false, null).backend());
        assertEquals(0, r.pick("nope", false, null).candidates());
        assertEquals(2, r.pick("api", false, null).candidates());
    }

    @Test
    void reconcileKeepsLearnedStateOfUnchangedBackendsAndResetsChangedOnes() {
        Router r = router("adaptive");
        r.reconcile(List.of(spec("a", "api", "h:1"), spec("b", "api", "h:2")));
        Backend a = r.all().get(0);
        a.observe(CFG, 50_000_000L, false, System.nanoTime());
        r.reconcile(List.of(spec("a", "api", "h:1"), spec("b", "api", "h:99"), spec("c", "api", "h:3")));
        assertSame(a, r.all().get(0), "unchanged backend keeps its object and signals");
        assertEquals("h:99", r.all().get(1).addr, "changed address means a new backend");
        assertEquals(3, r.all().size());
        r.reconcile(List.of(spec("c", "api", "h:3")));
        assertEquals(1, r.all().size());
    }

    @Test
    void reconcileAppliesWeightsAndResetsOmittedOnes() {
        Router r = router("adaptive");
        r.reconcile(List.of(new Spec("a", "s", "h:1", 4)));
        assertEquals(4, r.all().get(0).weight());
        r.reconcile(List.of(new Spec("a", "s", "h:1", 0)));
        assertEquals(1, r.all().get(0).weight());
    }

    @Test
    void movingABackendToAnotherServiceCreatesANewBackend() {
        Router r = router("adaptive");
        r.reconcile(List.of(spec("a", "one", "h:1")));
        Backend first = r.all().get(0);
        r.reconcile(List.of(spec("a", "two", "h:1")));
        assertNotSame(first, r.all().get(0));
        assertEquals(Map.of("two", "adaptive"), r.services());
    }

    @Test
    void resilientPickSkipsEjectedAndBreakerOpenBackends() {
        Router r = router("roundrobin");
        r.reconcile(List.of(spec("a", "api", "h:1"), spec("b", "api", "h:2"), spec("c", "api", "h:3")));
        Backend a = r.all().get(0);
        Backend b = r.all().get(1);
        b.breaker.forceOpen();
        a.breaker.reset();
        for (int i = 0; i < 20; i++) {
            Router.Pick p = r.pick("api", true, null);
            assertEquals(2, p.candidates());
            assertTrue(!p.backend().id.equals("b"));
        }
        assertNotNull(r.pick("api", false, null).backend()); // non-resilient mode ignores breakers
        assertEquals(3, r.pick("api", false, null).candidates());
    }

    @Test
    void pickHonoursTheExcludeSet() {
        Router r = router("roundrobin");
        r.reconcile(List.of(spec("a", "api", "h:1"), spec("b", "api", "h:2")));
        Backend a = r.all().get(0);
        for (int i = 0; i < 10; i++) {
            assertEquals("b", r.pick("api", false, Set.of(a)).backend().id);
        }
        assertNull(r.pick("api", false, Set.copyOf(r.all())).backend());
    }

    @Test
    void policyOverridesApplyPerServiceAndCanBeCleared() {
        Router r = router("adaptive");
        r.reconcile(List.of(spec("a", "api", "h:1"), spec("c", "web", "h:3")));
        r.setPolicies(Map.of("api", "roundrobin"));
        assertEquals(Map.of("api", "roundrobin", "web", "adaptive"), r.services());
        r.setPolicies(Map.of());
        assertEquals(Map.of("api", "adaptive", "web", "adaptive"), r.services());
        r.setPolicies(Map.of("later", "roundrobin")); // policy for a service that has no backends yet
        r.reconcile(List.of(spec("x", "later", "h:9")));
        assertEquals("roundrobin", r.services().get("later"));
    }

    @Test
    void breakerTransitionsAreRecordedAsEvents() throws Exception {
        Router r = router("adaptive");
        r.reconcile(List.of(spec("a", "api", "h:1")));
        r.all().get(0).breaker.forceOpen();
        TestUtil.eventually("breaker event", java.time.Duration.ofSeconds(2),
                () -> r.events.all().stream().anyMatch(e -> e.kind().equals("breaker") && e.detail().equals("closed->open")));
    }
}
