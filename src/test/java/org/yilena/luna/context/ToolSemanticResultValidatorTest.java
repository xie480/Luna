package org.yilena.luna.context;

import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.model.TaskState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSemanticResultValidatorTest {

    @Test
    void shouldRejectMissingSemanticFields() {
        ToolSemanticResultValidator validator = new ToolSemanticResultValidator();
        ToolSemanticResult result = ToolSemanticResult.builder()
                .toolStatus("DONE")
                .keyFacts(List.of())
                .businessImpact("")
                .unresolvedIssues(List.of())
                .nextStepHint("")
                .confidence(1.4)
                .semanticPayload(Map.of())
                .build();

        ToolSemanticResultValidator.ValidationResult validation = validator.validate(result);
        assertFalse(validation.valid());
        assertTrue(validation.issues().contains("invalid_status"));
        assertTrue(validation.issues().contains("missing_key_facts"));
    }

    @Test
    void shouldAcceptWellStructuredSemanticResult() {
        ToolSemanticResultValidator validator = new ToolSemanticResultValidator();
        ToolSemanticResult result = ToolSemanticResult.builder()
                .toolStatus("SUCCESS")
                .keyFacts(List.of("tool=search_knowledge"))
                .businessImpact("Knowledge evidence is ready")
                .unresolvedIssues(List.of())
                .nextStepHint("continue reasoning")
                .confidence(0.86)
                .semanticPayload(Map.of("status", "SUCCESS"))
                .build();

        ToolSemanticResultValidator.ValidationResult validation = validator.validate(result);
        assertTrue(validation.valid());
    }

    @Test
    void shouldRejectBudgetAndStateConflict() {
        ToolSemanticResultValidator validator = new ToolSemanticResultValidator();
        ToolSemanticResult result = ToolSemanticResult.builder()
                .toolStatus("SUCCESS")
                .keyFacts(List.of("tool=budget_tool"))
                .businessImpact("execution finished")
                .unresolvedIssues(List.of())
                .nextStepHint("continue reasoning")
                .confidence(0.9)
                .semanticPayload(Map.of(
                        "status", "SUCCESS",
                        "cost", 180,
                        "tool", "budget_tool",
                        "details", "x".repeat(8000)
                ))
                .build();
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .taskState(TaskRuntimeState.WAITING_APPROVAL)
                .tokenBudgetPlan(Map.of("task_procedures", 200))
                .taskStateEntity(TaskState.builder()
                        .taskId("t1")
                        .sessionId("s1")
                        .objective("demo")
                        .currentStage("WAITING_APPROVAL")
                        .currentNode("n1")
                        .confirmedSlots(Map.of("budget", 100))
                        .pendingQuestions(List.of())
                        .finishedSteps(List.of())
                        .failedSteps(List.of())
                        .retryCount(0)
                        .nextActionHint("continue")
                        .build())
                .build();

        ToolSemanticResultValidator.ValidationResult validation = validator.validate(result, contextPackage);
        assertFalse(validation.valid());
        assertTrue(validation.issues().contains("semantic_payload_budget_exceeded"));
        assertTrue(validation.issues().contains("budget_conflict_with_current_state"));
        assertTrue(validation.issues().contains("status_conflict_waiting_approval"));
        assertTrue(Boolean.TRUE.equals(validation.normalized().getSemanticPayload().get("budgetTrimmed")));
    }
}
