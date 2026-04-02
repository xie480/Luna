package org.yilena.luna.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticPreservingPrunerTest {

    @Test
    void shouldKeepCriticalFactsWhenBudgetIsTight() {
        SemanticPreservingPruner pruner = new SemanticPreservingPruner();
        Map<String, List<String>> sections = Map.of(
                "Current Task State", List.of("plan=Q2复盘; deadline=2026-04-10; budget=120000; must keep retry_count=2; long_note=" + "x".repeat(800))
        );
        Map<String, Integer> budget = Map.of("Current Task State", 60);

        SemanticPreservingPruner.PruneResult result = pruner.prune(sections, budget);
        String merged = String.join(" ", result.getSections().get("Current Task State"));

        assertTrue(merged.contains("2026-04-10"));
        assertTrue(merged.toLowerCase().contains("budget"));
        assertTrue(merged.toLowerCase().contains("retry"));
    }
}

