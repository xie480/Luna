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
                "Current Task State", List.of("plan=Q2复盘；deadline=2026-04-10；budget=120000；必须保留retry_count=2；long_note=" + "x".repeat(800))
        );
        Map<String, Integer> budget = Map.of("Current Task State", 60);

        SemanticPreservingPruner.PruneResult result = pruner.prune(sections, budget);
        String merged = String.join(" ", result.getSections().get("Current Task State"));

        assertTrue(merged.contains("2026-04-10"));
        assertTrue(merged.toLowerCase().contains("budget"));
        assertTrue(merged.toLowerCase().contains("retry"));
    }

    @Test
    void shouldPreservePendingIssuesAndLatestToolConclusion() {
        SemanticPreservingPruner pruner = new SemanticPreservingPruner();
        Map<String, List<String>> sections = Map.of(
                "Current Task State", List.of(
                        "pending_issues=missing_api_key",
                        "time_range=2026-04-01~2026-04-30",
                        "budget_limit=5000"
                ),
                "Tool Evidence", List.of("latest_tool_conclusion=upload failed due to 429"),
                "Recent Interaction", List.of("noise ".repeat(1200))
        );
        Map<String, Integer> budget = Map.of(
                "Current Task State", 40,
                "Tool Evidence", 40,
                "Recent Interaction", 20
        );

        SemanticPreservingPruner.PruneResult result = pruner.prune(sections, budget);
        String merged = result.getSections().values().stream().flatMap(List::stream).reduce("", (a, b) -> a + " " + b).toLowerCase();

        assertTrue(merged.contains("pending_issues"));
        assertTrue(merged.contains("2026-04-01"));
        assertTrue(merged.contains("latest_tool_conclusion"));
    }
}
