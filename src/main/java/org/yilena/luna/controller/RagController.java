package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.rag.api.RetrievalService;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/luna/api/rag")
@Tag(name = "RAG 检索接口")
public class RagController {

    private final RetrievalService retrievalService;
    private final TaskOrchestratorService taskOrchestratorService;

    @Operation(description = "执行一次受治理的 RAG 检索流程")
    @PostMapping("/retrieve")
    public ResponseEntity<RetrievalResponse> retrieve(@RequestBody RetrievalRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String sessionId = normalizeSessionId(request.getSessionId());
        String rawQuery = request.getQuery().trim();

        TaskOrchestrationResult orchestrationResult = taskOrchestratorService.orchestrateUserInput(sessionId, rawQuery);
        OrchestrationDecision decision = orchestrationResult == null ? null : orchestrationResult.getDecision();
        StructuredContextPackage contextPackage = orchestrationResult == null ? null : orchestrationResult.getContextPackage();
        InputReconstructionResult reconstructionResult = orchestrationResult == null ? null : orchestrationResult.getReconstructionResult();
        NodeWorksetResult nodeWorksetResult = taskOrchestratorService.orchestrateNodeWorkset(
                sessionId,
                rawQuery,
                decision,
                contextPackage,
                reconstructionResult
        );
        String governedQuery = nodeWorksetResult != null
                && nodeWorksetResult.getRagQuery() != null
                && !nodeWorksetResult.getRagQuery().isBlank()
                ? nodeWorksetResult.getRagQuery()
                : "";
        if (governedQuery.isBlank()) {
            Map<String, Object> rejectedMeta = new LinkedHashMap<>();
            rejectedMeta.put("governed", true);
            rejectedMeta.put("status", "rejected");
            rejectedMeta.put("reason", "RAG query governance failed: empty governed query");
            rejectedMeta.put("rawQuery", rawQuery);
            rejectedMeta.put("governedQuery", governedQuery);
            rejectedMeta.put("mcpDrivenInput", nodeWorksetResult == null ? "" : nodeWorksetResult.getMcpDrivenInput());
            return ResponseEntity.unprocessableEntity()
                    .body(RetrievalResponse.builder().meta(rejectedMeta).build());
        }

        RetrievalRequest governedRequest = RetrievalRequest.builder()
                .query(governedQuery)
                .sessionId(sessionId)
                .conversationContext(request.getConversationContext() == null ? List.of() : request.getConversationContext())
                .allowedRoutes(request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty()
                        ? RetrievalRoute.all()
                        : request.getAllowedRoutes())
                .sourceScope(request.getSourceScope() == null || request.getSourceScope().isEmpty()
                        ? RetrievalSource.all()
                        : request.getSourceScope())
                .options(request.getOptions())
                .build();

        RetrievalResponse response = retrievalService.retrieve(governedRequest);
        Map<String, Object> governedMeta = new LinkedHashMap<>();
        if (response != null && response.getMeta() != null) {
            governedMeta.putAll(response.getMeta());
        }
        governedMeta.put("governed", true);
        governedMeta.put("rawQuery", rawQuery);
        governedMeta.put("governedQuery", governedQuery);
        governedMeta.put("mcpDrivenInput", nodeWorksetResult == null ? "" : nodeWorksetResult.getMcpDrivenInput());

        RetrievalResponse governedResponse = response == null
                ? RetrievalResponse.builder().meta(governedMeta).build()
                : RetrievalResponse.builder()
                .route(response.getRoute())
                .rewrittenQuery(response.getRewrittenQuery())
                .evidences(response.getEvidences())
                .evidenceRoleGroups(response.getEvidenceRoleGroups())
                .meta(governedMeta)
                .build();
        return ResponseEntity.ok(governedResponse);
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "rag-governed-session";
        }
        return sessionId;
    }
}
