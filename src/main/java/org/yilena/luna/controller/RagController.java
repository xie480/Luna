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
@Tag(name = "RAG 检索接口", description = "提供受治理的检索增强查询能力")
/**
 * RAG 控制器，负责在检索前执行输入治理，再调用检索服务返回证据结果。
 */
public class RagController {

    /**
     * 检索服务，负责执行真正的 RAG 检索流程。
     */
    private final RetrievalService retrievalService;
    /**
     * 任务编排服务，负责在检索前进行输入治理和工作集选择。
     */
    private final TaskOrchestratorService taskOrchestratorService;

    /**
     * 执行一次受治理的 RAG 检索流程。
     *
     * 该接口会先对原始查询做任务编排和查询治理，只有得到可执行的受控查询后才会进入检索阶段。
     */
    @Operation(summary = "执行 RAG 检索", description = "对用户查询进行治理后执行检索增强流程，并返回检索证据与元信息")
    @PostMapping("/retrieve")
    public ResponseEntity<RetrievalResponse> retrieve(@RequestBody RetrievalRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String sessionId = normalizeSessionId(request.getSessionId());
        String rawQuery = request.getQuery().trim();

        /**
         * 先执行任务编排和节点工作集选择，生成更安全、更适合检索的治理查询。
         */
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
            /**
             * 当治理后查询为空时直接返回 422，明确告知前端本次检索被治理策略拒绝。
             */
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

        /**
         * 使用治理后的请求执行检索，并把治理元信息补回响应，方便前端排查命中过程。
         */
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

    /**
     * 为缺失的会话标识提供默认值，避免治理流程因空 sessionId 中断。
     */
    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "rag-governed-session";
        }
        return sessionId;
    }
}
