package org.yilena.luna.context;

import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.ToolSemanticResult;

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
}

