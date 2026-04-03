package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.entity.ChatRequest;
import org.yilena.luna.entity.PlanEdge;
import org.yilena.luna.entity.PlanInstance;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.entity.PlanPhase;
import org.yilena.luna.enums.PlanFinalStatus;
import org.yilena.luna.enums.PlanModelHint;
import org.yilena.luna.enums.PlanNodeStatus;
import org.yilena.luna.enums.PlanNodeType;
import org.yilena.luna.enums.PlanPhaseStatus;
import org.yilena.luna.enums.PlanRiskLevel;
import org.yilena.luna.enums.PlanStatus;
import org.yilena.luna.mapper.PlanEdgeMapper;
import org.yilena.luna.mapper.PlanInstanceMapper;
import org.yilena.luna.mapper.PlanNodeMapper;
import org.yilena.luna.mapper.PlanPhaseMapper;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.BlueprintValidationService;
import org.yilena.luna.service.ChatService;
import org.yilena.luna.service.MasterPlanningService;
import org.yilena.luna.service.PhaseExecutionService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.tools.PlanBlueprintTools;
import org.yilena.luna.tools.PlanEventTools;
import org.yilena.luna.tools.PlanNodeTools;
import org.yilena.luna.tools.PlanReportTools;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OpenClaw 計劃編排服務實現（Master Planner 版）
 *
 * 職責劃分：
 * - 本類負責計劃生命週期管理：創建、持久化、狀態流轉、報告生成
 * - 階段內節點調度委託給 {@link PhaseExecutionService}
 * - 事件推送通過 {@link PlanEventTools} 統一處理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanOrchestratorServiceImpl implements PlanOrchestratorService {

    private static final int DEFAULT_MAX_RETRY = 1;

    private final ObjectMapper objectMapper;
    private final PlanInstanceMapper planInstanceMapper;
    private final PlanNodeMapper planNodeMapper;
    private final PlanPhaseMapper planPhaseMapper;
    private final PlanEdgeMapper planEdgeMapper;

    private final PlanBlueprintTools planBlueprintTools;
    private final PlanNodeTools planNodeTools;
    private final PlanEventTools planEventTools;
    private final PlanReportTools planReportTools;
    private final ChatService chatService;
    private final ContextCompilerService contextCompilerService;
    private final InputReconstructionAgent inputReconstructionAgent;

    private final MasterPlanningService masterPlanningService;
    private final BlueprintValidationService blueprintValidationService;
    private final PhaseExecutionService phaseExecutionService;

    @Override
    public String createAndRunPlan(String sessionId, String userGoal) {
        return createAndRunPlan(sessionId, userGoal, true);
    }

    @Override
    public String createAndRunPlan(String sessionId, String userGoal, boolean callbackToChat) {
        String planId = null;
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "sessionId 不能為空");
            }
            if (userGoal == null || userGoal.isBlank()) {
                return error("PLAN_INVALID_INPUT", "userGoal 不能為空");
            }
            PlanInputContext planInputContext = reconstructPlanInput(sessionId, userGoal);
            InputReconstructionResult reconstructionResult = planInputContext == null ? null : planInputContext.reconstructionResult();
            StructuredContextPackage planningContextPackage = planInputContext == null ? null : planInputContext.contextPackage();
            PlanningIntent planningIntent = parsePlanningIntent(userGoal);
            String effectiveGoal = resolveEffectiveGoal(userGoal, planningIntent, reconstructionResult);

            planId = "plan-" + SnowflakeIdUtil.nextIdStr();
            int planVersion = 1;

            log.info("[Plan] 創建計劃, planId={}, sessionId={}, userGoal={}, effectiveGoal={}", planId, sessionId, userGoal, effectiveGoal);

            PlanInstance instance = PlanInstance.builder()
                    .planId(planId)
                    .sessionId(sessionId)
                    .userGoal(effectiveGoal)
                    .constraintsJson(Map.of(
                            "raw_user_goal", userGoal,
                            "planning_intent", planningIntent.meta(),
                            "input_reconstruction", reconstructionResult == null
                                    ? Map.of()
                                    : objectMapper.convertValue(reconstructionResult, new TypeReference<Map<String, Object>>() {})
                    ))
                    .planVersion(planVersion)
                    .status(PlanStatus.PENDING)
                    .currentLoopIndex(0)
                    .planningModel("master-planner-code")
                    .startedAt(LocalDateTime.now())
                    .build();
            planInstanceMapper.insert(instance);

            emitPlanEvent(planId, "", "", "PLAN_CREATED", "INFO",
                    Map.of("planId", planId, "sessionId", sessionId, "userGoal", userGoal, "effectiveGoal", effectiveGoal,
                            "planVersion", planVersion, "status", "PENDING",
                            "message", "計劃已創建", "timestamp", System.currentTimeMillis()));
            emitFrontProgress(planId, "PLAN_CREATED", "計劃已建立，正在生成藍圖", 0, 0, 0, 0);

            log.info("[Plan] 調用 MasterPlanningService 生成全局藍圖, planId={}", planId);
            Map<String, Object> blueprint = masterPlanningService.generateBlueprint(
                    planId,
                    sessionId,
                    userGoal,
                    reconstructionResult,
                    extractPlanningKnowledgeEvidence(planningContextPackage),
                    extractPlanningWorkflowHints(planningContextPackage)
            );

            String validateErr = blueprintValidationService.validate(blueprint);
            if (validateErr != null) {
                log.error("[Plan] 藍圖校驗失敗, planId={}, err={}", planId, validateErr);
                updatePlanStatus(planId, PlanStatus.FAILED, validateErr);
                emitPlanFinished(planId, "FAILED", validateErr);
                emitFrontProgress(planId, "PLAN_FAILED", "藍圖校驗失敗：" + validateErr, 0, 0, 0, 0);
                return error("PLAN_BLUEPRINT_INVALID", validateErr);
            }

            String saveResult = planBlueprintTools.savePlanBlueprint(
                    planId, planVersion,
                    objectMapper.writeValueAsString(blueprint),
                    "master-planner-code",
                    LocalDateTime.now().toString()
            );
            if (isError(saveResult)) {
                log.error("[Plan] 保存藍圖失敗, planId={}, result={}", planId, saveResult);
                markPlanFailed(planId, "保存藍圖失敗");
                emitPlanFinished(planId, "FAILED", "保存藍圖失敗");
                emitFrontProgress(planId, "PLAN_FAILED", "藍圖保存失敗", 0, 0, 0, 0);
                return saveResult;
            }

            materializePhasesAndNodes(planId, blueprint);
            buildEdgesFromBlueprint(planId, blueprint);

            updatePlanStatus(planId, PlanStatus.RUNNING, null);

            List<PlanPhase> orderedPhases = loadOrderedPhases(planId);
            if (orderedPhases.isEmpty()) {
                log.error("[Plan] 未找到可執行階段, planId={}", planId);
                markPlanFailed(planId, "未找到可執行階段");
                emitPlanFinished(planId, "FAILED", "未找到可執行階段");
                emitFrontProgress(planId, "PLAN_FAILED", "未找到可執行階段", 0, 0, 0, 0);
                return error("PLAN_PHASE_EMPTY", "未找到可執行階段");
            }

            emitFrontProgress(planId, "PLAN_RUNNING", "藍圖已就緒，開始分階段執行", orderedPhases.size(), 0, 0, 0);
            log.info("[Plan] 開始按階段執行, planId={}, phaseCount={}", planId, orderedPhases.size());

            List<Map<String, Object>> phaseResults = new ArrayList<>();
            boolean hasPhaseFailure = false;

            for (PlanPhase phase : orderedPhases) {
                String phaseId = phase.getPhaseId();
                int phaseOrder = phase.getPhaseOrder() == null ? 0 : phase.getPhaseOrder();

                log.info("[Plan] 開始階段, planId={}, phaseId={}, phaseOrder={}, name={}",
                        planId, phaseId, phaseOrder, phase.getName());

                markPhaseStatus(phase, PlanPhaseStatus.RUNNING, true, false);

                emitPlanEvent(planId, phaseId, "", "PLAN_PHASE_STARTED", "INFO",
                        Map.of("planId", planId, "phaseId", phaseId, "phaseOrder", phaseOrder,
                                "status", "RUNNING", "message", "階段開始執行",
                                "timestamp", System.currentTimeMillis()));
                emitFrontPhaseProgress(planId, phase, "RUNNING", "階段開始執行");

                String phaseResult = phaseExecutionService.executePhase(planId, phase, sessionId);

                phaseResults.add(Map.of(
                        "phaseId", phaseId,
                        "phaseOrder", phaseOrder,
                        "result", safeParse(phaseResult)
                ));

                boolean phaseError = isError(phaseResult);
                if (phaseError) {
                    hasPhaseFailure = true;
                    markPhaseStatus(phase, PlanPhaseStatus.FAILED, false, true);

                    emitPlanEvent(planId, phaseId, "", "PLAN_PHASE_FINISHED", "WARN",
                            Map.of("planId", planId, "phaseId", phaseId, "phaseOrder", phaseOrder,
                                    "status", "FAILED", "message", "階段執行失敗",
                                    "timestamp", System.currentTimeMillis()));

                    String phaseErrMsg = extractErrorMessage(phaseResult);
                    String phaseErrCode = extractErrorCode(phaseResult);
                    Object phaseErrData = extractDataObj(phaseResult);

                    log.error("[Plan] 階段執行失敗，終止後續階段, planId={}, phaseId={}, phaseOrder={}, errorCode={}, errorMessage={}, rawResult={}, data={}",
                            planId, phaseId, phaseOrder, phaseErrCode, phaseErrMsg, phaseResult, toJsonQuiet(phaseErrData));

                    emitFrontPhaseProgress(planId, phase, "FAILED",
                            "階段失敗：" + (phaseErrMsg == null || phaseErrMsg.isBlank() ? "未知錯誤" : phaseErrMsg));
                    break;
                } else {
                    markPhaseStatus(phase, PlanPhaseStatus.SUCCESS, false, true);

                    emitPlanEvent(planId, phaseId, "", "PLAN_PHASE_FINISHED", "INFO",
                            Map.of("planId", planId, "phaseId", phaseId, "phaseOrder", phaseOrder,
                                    "status", "SUCCESS", "message", "階段執行成功",
                                    "timestamp", System.currentTimeMillis()));

                    emitFrontPhaseProgress(planId, phase, "SUCCESS", "階段執行成功");
                    log.info("[Plan] 階段執行成功, planId={}, phaseId={}, phaseOrder={}", planId, phaseId, phaseOrder);
                }
            }

            String reportResult = finalizeAndReport(planId);

            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("planId", planId);
            merged.put("phaseResults", phaseResults);
            merged.put("reportResult", safeParse(reportResult));

            if (hasPhaseFailure) {
                updatePlanStatus(planId, PlanStatus.FAILED, "階段執行失敗");
                emitPlanFinished(planId, "FAILED", "階段執行失敗，已生成報告");
                merged.put("status", "error");
                merged.put("message", "計劃階段執行失敗，已生成報告");
                emitFrontProgress(planId, "PLAN_FINISHED", "任務執行失敗，報告已生成", countPhases(planId), countFinishedPhases(planId), countSuccessNodes(planId), countFailedNodes(planId));
            } else {
                updatePlanStatus(planId, PlanStatus.SUCCESS, null);
                emitPlanFinished(planId, "SUCCESS", "計劃執行成功並生成報告");
                merged.put("status", "success");
                merged.put("message", "計劃多階段執行成功並生成報告");
                emitFrontProgress(planId, "PLAN_FINISHED", "任務執行成功，報告已生成", countPhases(planId), countFinishedPhases(planId), countSuccessNodes(planId), countFailedNodes(planId));
            }

            String finalResultJson = objectMapper.writeValueAsString(merged);
            if (callbackToChat) {
                sendFinalResultToLuna(sessionId, finalResultJson);
            }

            log.info("[Plan] 計劃執行完畢, planId={}, status={}", planId, merged.get("status"));
            return finalResultJson;

        } catch (Exception e) {
            log.error("[Plan] createAndRunPlan 發生未捕獲異常, planId={}, err={}", planId, e.getMessage(), e);
            if (planId != null) {
                updatePlanStatus(planId, PlanStatus.FAILED, "創建並執行計劃失敗: " + e.getMessage());
                emitPlanFinished(planId, "FAILED", "創建並執行計劃失敗: " + e.getMessage());
                emitFrontProgress(planId, "PLAN_FAILED", "任務中斷：" + e.getMessage(), countPhases(planId), countFinishedPhases(planId), countSuccessNodes(planId), countFailedNodes(planId));
            }
            String errJson = error("PLAN_CREATE_RUN_FAILED", "創建並執行計劃失敗: " + e.getMessage());
            try {
                if (callbackToChat && sessionId != null && !sessionId.isBlank()) {
                    sendFinalResultToLuna(sessionId, errJson);
                }
            } catch (Exception ex) {
                log.warn("[Plan] 發送最終失敗結果給 Luna 失敗（不中斷）, sessionId={}, err={}", sessionId, ex.getMessage());
            }
            return errJson;
        }
    }

    @Override
    public String runPhase(String planId, String phaseId) {
        try {
            if (planId == null || planId.isBlank() || phaseId == null || phaseId.isBlank()) {
                return error("PHASE_INVALID_INPUT", "planId 和 phaseId 不能為空");
            }

            PlanPhase phase = planPhaseMapper.selectById(phaseId);
            if (phase == null || !planId.equals(phase.getPlanId())) {
                return error("PHASE_NOT_FOUND", "階段不存在或不屬於該計劃");
            }

            String sessionId = resolveSessionId(planId);
            log.info("[Plan] 單獨執行階段, planId={}, phaseId={}, sessionId={}", planId, phaseId, sessionId);

            markPhaseStatus(phase, PlanPhaseStatus.RUNNING, true, false);
            emitFrontPhaseProgress(planId, phase, "RUNNING", "手動觸發階段執行");
            String result = phaseExecutionService.executePhase(planId, phase, sessionId);

            if (isError(result)) {
                String errMsg = extractErrorMessage(result);
                String errCode = extractErrorCode(result);
                Object errData = extractDataObj(result);
                log.error("[Plan] runPhase 返回錯誤, planId={}, phaseId={}, errorCode={}, errorMessage={}, rawResult={}, data={}",
                        planId, phaseId, errCode, errMsg, result, toJsonQuiet(errData));
                markPhaseStatus(phase, PlanPhaseStatus.FAILED, false, true);
                emitFrontPhaseProgress(planId, phase, "FAILED", "階段執行失敗：" + errMsg);
            } else {
                markPhaseStatus(phase, PlanPhaseStatus.SUCCESS, false, true);
                emitFrontPhaseProgress(planId, phase, "SUCCESS", "階段執行成功");
            }

            return result;
        } catch (Exception e) {
            log.error("[Plan] runPhase 異常, planId={}, phaseId={}, err={}", planId, phaseId, e.getMessage(), e);
            return error("PHASE_EXECUTION_FAILED", "階段執行失敗: " + e.getMessage());
        }
    }

    @Override
    public String finalizeAndReport(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能為空");
            }

            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) {
                return error("PLAN_NOT_FOUND", "計劃不存在: " + planId);
            }

            List<PlanPhase> phases = planPhaseMapper.selectList(
                    new LambdaQueryWrapper<PlanPhase>()
                            .eq(PlanPhase::getPlanId, planId)
                            .orderByAsc(PlanPhase::getPhaseOrder)
            );
            List<PlanNode> nodes = planNodeMapper.selectList(
                    new LambdaQueryWrapper<PlanNode>()
                            .eq(PlanNode::getPlanId, planId)
                            .orderByAsc(PlanNode::getCreatedAt)
            );

            long success = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SUCCESS).count();
            long failed = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.FAILED).count();
            long skipped = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SKIPPED).count();

            PlanFinalStatus finalStatus;
            String finalStatusText;
            if (failed == 0 && success > 0) {
                finalStatus = PlanFinalStatus.SUCCESS;
                finalStatusText = "SUCCESS";
            } else if (success > 0) {
                finalStatus = PlanFinalStatus.PARTIAL;
                finalStatusText = "PARTIAL";
            } else {
                finalStatus = PlanFinalStatus.FAILED;
                finalStatusText = "FAILED";
            }

            emitFrontProgress(planId, "REPORT_GENERATING", "正在生成任務報告", phases.size(), countFinishedPhases(planId), success, failed);

            String html = buildReportHtml(instance, phases, nodes, finalStatusText);
            String writeResult = planReportTools.writeHtmlReportFile(
                    planId, html, planId + ".html", "./data/reports", finalStatusText, "FAILED"
            );
            if (isError(writeResult)) {
                log.error("[Plan] 寫入報告文件失敗, planId={}, result={}", planId, writeResult);
                emitFrontProgress(planId, "REPORT_FAILED", "報告寫入失敗", phases.size(), countFinishedPhases(planId), success, failed);
                return writeResult;
            }

            Map<String, Object> writePayload = extractDataPayload(writeResult);
            String reportPath = asText(writePayload.get("reportPath"));
            String reportUrl = asText(writePayload.get("reportUrl"));

            String openFlag = "FAILED";
            try {
                String openResult = planReportTools.openBrowserWithFile(reportPath);
                Map<String, Object> openPayload = extractDataPayload(openResult);
                openFlag = asText(openPayload.getOrDefault("openResult", "FAILED"));
            } catch (Exception e) {
                log.warn("[Plan] 打開瀏覽器失敗（不中斷）, planId={}, err={}", planId, e.getMessage());
            }

            instance.setFinalStatus(finalStatus);
            instance.setFinishedAt(LocalDateTime.now());
            instance.setStatus(PlanFinalStatus.SUCCESS.equals(finalStatus) ? PlanStatus.SUCCESS : PlanStatus.FAILED);
            planInstanceMapper.updateById(instance);

            emitPlanEvent(planId, "", "", "PLAN_REPORT_READY", "INFO",
                    Map.of("planId", planId, "status", "SUCCESS",
                            "message", "任務報告已生成",
                            "reportPath", reportPath, "reportUrl", reportUrl,
                            "openResult", openFlag,
                            "timestamp", System.currentTimeMillis()));

            emitFrontProgress(planId, "REPORT_READY",
                    "報告已生成" + ("SUCCESS".equals(openFlag) ? "並已嘗試打開" : "，可手動打開"),
                    phases.size(), countFinishedPhases(planId), success, failed);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("planId", planId);
            out.put("finalStatus", finalStatusText);
            out.put("reportPath", reportPath);
            out.put("reportUrl", reportUrl);
            out.put("openResult", openFlag);
            out.put("nodeTotal", nodes.size());
            out.put("nodeSuccess", success);
            out.put("nodeFailed", failed);
            out.put("nodeSkipped", skipped);

            return objectMapper.writeValueAsString(out);

        } catch (Exception e) {
            log.error("[Plan] finalizeAndReport 異常, planId={}, err={}", planId, e.getMessage(), e);
            emitFrontProgress(planId, "REPORT_FAILED", "報告生成失敗：" + e.getMessage(), countPhases(planId), countFinishedPhases(planId), countSuccessNodes(planId), countFailedNodes(planId));
            return error("PLAN_REPORT_FAILED", "報告生成失敗: " + e.getMessage());
        }
    }

    @Override
    public String getPlanGraph(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能為空");
            }

            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) {
                return error("PLAN_NOT_FOUND", "計劃不存在: " + planId);
            }

            List<PlanPhase> phases = planPhaseMapper.selectList(
                    new LambdaQueryWrapper<PlanPhase>()
                            .eq(PlanPhase::getPlanId, planId)
                            .orderByAsc(PlanPhase::getPhaseOrder)
            );
            List<PlanNode> nodes = planNodeMapper.selectList(
                    new LambdaQueryWrapper<PlanNode>()
                            .eq(PlanNode::getPlanId, planId)
                            .orderByAsc(PlanNode::getCreatedAt)
            );
            List<PlanEdge> edges = planEdgeMapper.selectList(
                    new LambdaQueryWrapper<PlanEdge>()
                            .eq(PlanEdge::getPlanId, planId)
                            .orderByAsc(PlanEdge::getId)
            );

            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("planId", planId);
            graph.put("sessionId", instance.getSessionId());
            graph.put("userGoal", instance.getUserGoal());
            graph.put("status", instance.getStatus() != null ? instance.getStatus().getValue() : "");
            graph.put("finalStatus", instance.getFinalStatus() != null ? instance.getFinalStatus().getValue() : "");
            graph.put("planVersion", instance.getPlanVersion());

            graph.put("phases", phases.stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("phaseId", p.getPhaseId());
                m.put("phaseOrder", p.getPhaseOrder());
                m.put("name", p.getName());
                m.put("objective", p.getObjective());
                m.put("status", p.getStatus() != null ? p.getStatus().getValue() : "");
                return m;
            }).toList());

            graph.put("nodes", nodes.stream().map(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", n.getNodeId());
                m.put("phaseId", n.getPhaseId());
                m.put("name", n.getName());
                m.put("nodeType", n.getNodeType() != null ? n.getNodeType().getValue() : "");
                m.put("status", n.getStatus() != null ? n.getStatus().getValue() : "");
                m.put("riskLevel", n.getRiskLevel() != null ? n.getRiskLevel().getValue() : "");
                m.put("retryCount", n.getRetryCount() == null ? 0 : n.getRetryCount());
                m.put("maxRetry", n.getMaxRetry() == null ? DEFAULT_MAX_RETRY : n.getMaxRetry());
                m.put("costMs", n.getCostMs() == null ? 0 : n.getCostMs());
                m.put("failReason", n.getFailReason() == null ? "" : n.getFailReason());
                m.put("dependencies", n.getDependencies() == null ? List.of() : n.getDependencies());
                m.put("outputForNext", n.getOutputForNext() == null ? Map.of() : n.getOutputForNext());
                return m;
            }).toList());

            graph.put("edges", edges.stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fromNodeId", e.getFromNodeId());
                m.put("toNodeId", e.getToNodeId());
                m.put("conditionExpr", e.getConditionExpr() == null ? "" : e.getConditionExpr());
                return m;
            }).toList());

            Map<String, Long> nodeStats = nodes.stream().collect(Collectors.groupingBy(
                    n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(),
                    Collectors.counting()
            ));
            graph.put("nodeStats", nodeStats);

            return objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            log.error("[Plan] getPlanGraph 異常, planId={}, err={}", planId, e.getMessage(), e);
            return error("PLAN_GRAPH_FAILED", "獲取計劃圖譜失敗: " + e.getMessage());
        }
    }

    private void sendFinalResultToLuna(String sessionId, String finalResultJson) {
        try {
            AuthContextHolder.setSessionId(sessionId);

            ChatRequest req = new ChatRequest();
            String prompt = PromptTemplates.PLAN_FINAL_RESULT_TO_LUNA_PROMPT.formatted(finalResultJson);

            // 兼容 ChatRequest 可能不存在 setMessage 字段的情况
            // 优先尝试 setMessage，其次 fallback 到 setContent / setPrompt
            boolean assigned = trySetChatRequestField(req, "setMessage", prompt)
                    || trySetChatRequestField(req, "setContent", prompt)
                    || trySetChatRequestField(req, "setPrompt", prompt);

            if (!assigned) {
                throw new IllegalStateException("ChatRequest 未找到可用文本字段 setter（setMessage/setContent/setPrompt）");
            }

            chatService.chat(req);
            log.info("[Plan] 最终结果已交由 Luna 生成人设化回复, sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[Plan] 最终结果交给 Luna 失败（不中断）, sessionId={}, err={}", sessionId, e.getMessage());
        } finally {
            AuthContextHolder.clear();
        }
    }

    private boolean trySetChatRequestField(ChatRequest req, String methodName, String value) {
        try {
            req.getClass().getMethod(methodName, String.class).invoke(req, value);
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private void emitFrontProgress(String planId, String stage, String message,
                                   int phaseTotal, int phaseFinished, long nodeSuccess, long nodeFailed) {
        try {
            int percent = 0;
            if (phaseTotal > 0) {
                percent = Math.max(0, Math.min(100, (int) ((phaseFinished * 100.0) / phaseTotal)));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", planId);
            payload.put("stage", stage);
            payload.put("message", message);
            payload.put("phaseTotal", phaseTotal);
            payload.put("phaseFinished", phaseFinished);
            payload.put("nodeSuccess", nodeSuccess);
            payload.put("nodeFailed", nodeFailed);
            payload.put("progressPercent", percent);
            payload.put("timestamp", System.currentTimeMillis());
            planEventTools.emitPlanEventSse("default", "PLAN_FRONT_PROGRESS", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[Plan] emitFrontProgress 失败（不中断）, planId={}, stage={}, err={}", planId, stage, e.getMessage());
        }
    }

    private void emitFrontPhaseProgress(String planId, PlanPhase phase, String phaseStatus, String message) {
        int phaseTotal = countPhases(planId);
        int phaseFinished = countFinishedPhases(planId);
        emitFrontProgress(planId, "PHASE_" + phaseStatus, message, phaseTotal, phaseFinished, countSuccessNodes(planId), countFailedNodes(planId));
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", planId);
            payload.put("phaseId", phase.getPhaseId());
            payload.put("phaseOrder", phase.getPhaseOrder());
            payload.put("phaseName", phase.getName());
            payload.put("phaseStatus", phaseStatus);
            payload.put("message", message);
            payload.put("timestamp", System.currentTimeMillis());
            planEventTools.emitPlanEventSse("default", "PLAN_PHASE_PROGRESS", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[Plan] emitFrontPhaseProgress 失败（不中断）, planId={}, phaseId={}, err={}", planId, phase.getPhaseId(), e.getMessage());
        }
    }

    private int countPhases(String planId) {
        if (planId == null || planId.isBlank()) return 0;
        return Math.toIntExact(planPhaseMapper.selectCount(new LambdaQueryWrapper<PlanPhase>().eq(PlanPhase::getPlanId, planId)));
    }

    private int countFinishedPhases(String planId) {
        if (planId == null || planId.isBlank()) return 0;
        return Math.toIntExact(planPhaseMapper.selectCount(new LambdaQueryWrapper<PlanPhase>()
                .eq(PlanPhase::getPlanId, planId)
                .in(PlanPhase::getStatus, PlanPhaseStatus.SUCCESS, PlanPhaseStatus.FAILED)));
    }

    private long countSuccessNodes(String planId) {
        if (planId == null || planId.isBlank()) return 0;
        return planNodeMapper.selectCount(new LambdaQueryWrapper<PlanNode>()
                .eq(PlanNode::getPlanId, planId)
                .eq(PlanNode::getStatus, PlanNodeStatus.SUCCESS));
    }

    private long countFailedNodes(String planId) {
        if (planId == null || planId.isBlank()) return 0;
        return planNodeMapper.selectCount(new LambdaQueryWrapper<PlanNode>()
                .eq(PlanNode::getPlanId, planId)
                .eq(PlanNode::getStatus, PlanNodeStatus.FAILED));
    }

    private void materializePhasesAndNodes(String planId, Map<String, Object> blueprint) throws Exception {
        List<Map<String, Object>> phaseDefs = asListOfMap(blueprint.get("phases"));
        List<Map<String, Object>> nodeDefs = asListOfMap(blueprint.get("nodes"));

        Map<String, String> phaseIdMap = new LinkedHashMap<>();
        Map<String, List<String>> phaseNodeIds = new LinkedHashMap<>();

        int phaseIdx = 1;
        for (Map<String, Object> p : phaseDefs) {
            String rawPhaseId = text(p.get("phaseId"));
            if (rawPhaseId.isBlank()) rawPhaseId = "phase-" + phaseIdx;
            String phaseId = normalizeScopedId(planId, rawPhaseId);

            phaseIdMap.put(rawPhaseId, phaseId);
            phaseIdMap.put(phaseId, phaseId);

            PlanPhase phase = PlanPhase.builder()
                    .phaseId(phaseId)
                    .planId(planId)
                    .phaseOrder(intVal(p.get("phaseOrder"), phaseIdx))
                    .name(text(p.get("name")))
                    .objective(text(p.get("objective")))
                    .entryCriteria(text(p.get("entryCriteria")))
                    .exitCriteria(text(p.get("exitCriteria")))
                    .status(PlanPhaseStatus.PENDING)
                    .build();
            planPhaseMapper.insert(phase);
            phaseNodeIds.put(phaseId, new ArrayList<>());
            phaseIdx++;
        }

        if (phaseNodeIds.isEmpty()) {
            String fallbackPhaseId = normalizeScopedId(planId, "phase-1");
            PlanPhase fallback = PlanPhase.builder()
                    .phaseId(fallbackPhaseId).planId(planId).phaseOrder(1)
                    .name("PHASE-1").objective("默認階段").status(PlanPhaseStatus.PENDING).build();
            planPhaseMapper.insert(fallback);
            phaseNodeIds.put(fallbackPhaseId, new ArrayList<>());
            phaseIdMap.put("phase-1", fallbackPhaseId);
            phaseIdMap.put(fallbackPhaseId, fallbackPhaseId);
        }

        String defaultPhaseId = phaseNodeIds.keySet().stream().findFirst()
                .orElse(normalizeScopedId(planId, "phase-1"));

        Map<String, String> nodeIdMap = new LinkedHashMap<>();
        int nodeIdx = 1;
        for (Map<String, Object> n : nodeDefs) {
            String rawNodeId = text(n.get("nodeId"));
            if (rawNodeId.isBlank()) rawNodeId = "node-" + nodeIdx;
            String nodeId = normalizeScopedId(planId, rawNodeId);

            nodeIdMap.put(rawNodeId, nodeId);
            nodeIdMap.put(nodeId, nodeId);

            String rawPhaseId = text(n.get("phaseId"));
            String phaseId = phaseIdMap.getOrDefault(rawPhaseId, defaultPhaseId);
            if (!phaseNodeIds.containsKey(phaseId)) phaseId = defaultPhaseId;

            PlanNode node = PlanNode.builder()
                    .nodeId(nodeId)
                    .planId(planId)
                    .phaseId(phaseId)
                    .name(text(n.get("name")))
                    .nodeType(parseNodeType(text(n.get("nodeType"))))
                    .capabilityType(resolveCapabilityType(n))
                    .capabilityName(resolveCapabilityName(n))
                    .serverCode(resolveServerCode(n))
                    .inputJson(asMap(n.get("inputJson")))
                    .resolvedInputJson(resolveResolvedInputJson(n))
                    .expectedOutputSchema(asMap(n.get("expectedOutputSchema")))
                    .dependencies(remapStringListIds(asStringList(n.get("dependencies")), nodeIdMap, planId))
                    .parallelGroup(text(n.get("parallelGroup")))
                    .status(PlanNodeStatus.PENDING)
                    .approvalRequired(resolveApprovalRequired(n))
                    .approvalStatus(resolveApprovalStatus(n))
                    .retryPolicy(asMap(n.get("retryPolicy")))
                    .retryCount(intVal(n.get("retryCount"), 0))
                    .maxRetry(intVal(n.get("maxRetry"), DEFAULT_MAX_RETRY))
                    .modelHint(parseModelHint(text(n.get("modelHint"))))
                    .resourceHint(asMap(n.get("resourceHint")))
                    .riskLevel(parseRiskLevel(text(n.get("riskLevel"))))
                    .build();

            planNodeMapper.insert(node);
            phaseNodeIds.computeIfAbsent(phaseId, k -> new ArrayList<>()).add(nodeId);
            nodeIdx++;
        }

        for (Map.Entry<String, List<String>> e : phaseNodeIds.entrySet()) {
            PlanPhase phase = planPhaseMapper.selectById(e.getKey());
            if (phase != null) {
                phase.setNodeIds(e.getValue());
                planPhaseMapper.updateById(phase);
            }
        }

        blueprint.put("__phaseIdMap", phaseIdMap);
        blueprint.put("__nodeIdMap", nodeIdMap);
    }

    private void buildEdgesFromBlueprint(String planId, Map<String, Object> blueprint) {
        try {
            List<Map<String, Object>> edges = asListOfMap(blueprint.get("edges"));
            Map<String, String> nodeIdMap = asStringMap(blueprint.get("__nodeIdMap"));

            for (Map<String, Object> e : edges) {
                String rawFrom = text(e.get("fromNodeId"));
                String rawTo = text(e.get("toNodeId"));
                String from = resolveMappedOrScopedId(nodeIdMap, planId, rawFrom);
                String to = resolveMappedOrScopedId(nodeIdMap, planId, rawTo);

                if (from.isBlank() || to.isBlank()) continue;

                boolean fromExists = planNodeMapper.selectCount(
                        new LambdaQueryWrapper<PlanNode>()
                                .eq(PlanNode::getPlanId, planId)
                                .eq(PlanNode::getNodeId, from)) > 0;
                boolean toExists = planNodeMapper.selectCount(
                        new LambdaQueryWrapper<PlanNode>()
                                .eq(PlanNode::getPlanId, planId)
                                .eq(PlanNode::getNodeId, to)) > 0;
                if (!fromExists || !toExists) {
                    log.warn("[Plan] 邊引用的節點不存在，跳過, planId={}, from={}, to={}", planId, from, to);
                    continue;
                }

                long exists = planEdgeMapper.selectCount(
                        new LambdaQueryWrapper<PlanEdge>()
                                .eq(PlanEdge::getPlanId, planId)
                                .eq(PlanEdge::getFromNodeId, from)
                                .eq(PlanEdge::getToNodeId, to));
                if (exists == 0) {
                    planEdgeMapper.insert(PlanEdge.builder()
                            .planId(planId)
                            .fromNodeId(from)
                            .toNodeId(to)
                            .conditionExpr(text(e.get("conditionExpr")))
                            .build());
                }
            }
            log.info("[Plan] 構建邊完成, planId={}, edgeCount={}", planId, edges.size());
        } catch (Exception ex) {
            log.warn("[Plan] buildEdgesFromBlueprint 失敗（不中斷）, planId={}, err={}", planId, ex.getMessage());
        }
    }

    private void updatePlanStatus(String planId, PlanStatus status, String errMsg) {
        PlanInstance p = planInstanceMapper.selectById(planId);
        if (p == null) return;
        p.setStatus(status);
        p.setErrorMessage(errMsg);
        if (PlanStatus.SUCCESS.equals(status) || PlanStatus.FAILED.equals(status) || PlanStatus.CANCELLED.equals(status)) {
            p.setFinishedAt(LocalDateTime.now());
        }
        planInstanceMapper.updateById(p);
        log.info("[Plan] 計劃狀態更新, planId={}, status={}, errMsg={}", planId, status, errMsg);
    }

    private void markPlanFailed(String planId, String reason) {
        updatePlanStatus(planId, PlanStatus.FAILED, reason);
    }

    private void markPhaseStatus(PlanPhase phase, PlanPhaseStatus status, boolean markStart, boolean markFinish) {
        if (phase == null) return;
        phase.setStatus(status);
        if (markStart && phase.getStartedAt() == null) phase.setStartedAt(LocalDateTime.now());
        if (markFinish) phase.setFinishedAt(LocalDateTime.now());
        planPhaseMapper.updateById(phase);
    }

    private List<PlanPhase> loadOrderedPhases(String planId) {
        return planPhaseMapper.selectList(
                new LambdaQueryWrapper<PlanPhase>()
                        .eq(PlanPhase::getPlanId, planId)
                        .orderByAsc(PlanPhase::getPhaseOrder)
        );
    }

    private String resolveSessionId(String planId) {
        PlanInstance p = planInstanceMapper.selectById(planId);
        if (p == null || p.getSessionId() == null || p.getSessionId().isBlank()) return "plan-default-session";
        return p.getSessionId();
    }

    private void emitPlanEvent(String planId, String phaseId, String nodeId,
                               String eventType, String level, Map<String, Object> payload) {
        try {
            planEventTools.emitPlanEvent(
                    "default",
                    planId == null ? "" : planId,
                    phaseId == null ? "" : phaseId,
                    nodeId == null ? "" : nodeId,
                    level == null ? "INFO" : level,
                    eventType,
                    objectMapper.writeValueAsString(payload == null ? Map.of() : payload),
                    UUID.randomUUID().toString()
            );
        } catch (Exception e) {
            log.warn("[Plan] emitPlanEvent 失敗（不中斷）, planId={}, eventType={}, err={}", planId, eventType, e.getMessage());
        }
    }

    private void emitPlanFinished(String planId, String finalStatus, String message) {
        PlanInstance p = planInstanceMapper.selectById(planId);
        int planVersion = p == null || p.getPlanVersion() == null ? 0 : p.getPlanVersion();
        String sessionId = p == null || p.getSessionId() == null ? "" : p.getSessionId();

        emitPlanEvent(planId, "", "", "PLAN_FINISHED", "INFO",
                Map.of("planId", planId, "status", finalStatus, "message", message,
                        "planVersion", planVersion, "sessionId", sessionId,
                        "timestamp", System.currentTimeMillis()));
    }

    private String buildReportHtml(PlanInstance instance, List<PlanPhase> phases, List<PlanNode> nodes, String finalStatus) {
        long success = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SUCCESS).count();
        long failed = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.FAILED).count();
        long skipped = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SKIPPED).count();

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/>")
                .append("<title>OpenClaw 任務報告 - ").append(instance.getPlanId()).append("</title>")
                .append("<style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;padding:24px;background:#f7f8fa;color:#1f2937}")
                .append("h1,h2{margin:8px 0}.card{background:#fff;border-radius:10px;padding:16px;margin:12px 0;box-shadow:0 2px 8px rgba(0,0,0,.06)}")
                .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #e5e7eb;padding:8px;text-align:left;font-size:13px}")
                .append(".ok{color:#16a34a}.bad{color:#dc2626}.warn{color:#d97706}")
                .append("</style></head><body>");

        sb.append("<h1>OpenClaw 任務報告</h1>");
        sb.append("<div class='card'>")
                .append("<p><b>計劃ID：</b>").append(escapeHtml(instance.getPlanId())).append("</p>")
                .append("<p><b>會話ID：</b>").append(escapeHtml(instance.getSessionId())).append("</p>")
                .append("<p><b>用戶目標：</b>").append(escapeHtml(instance.getUserGoal())).append("</p>")
                .append("<p><b>最終狀態：</b>").append(escapeHtml(finalStatus)).append("</p>")
                .append("<p><b>創建時間：</b>").append(instance.getCreatedAt() == null ? "" : instance.getCreatedAt()).append("</p>")
                .append("<p><b>結束時間：</b>").append(LocalDateTime.now()).append("</p>")
                .append("</div>");

        sb.append("<div class='card'><h2>節點統計</h2>")
                .append("<p>總節點：").append(nodes.size())
                .append("，<span class='ok'>成功：").append(success).append("</span>")
                .append("，<span class='bad'>失敗：").append(failed).append("</span>")
                .append("，<span class='warn'>跳過：").append(skipped).append("</span></p>")
                .append("</div>");

        sb.append("<div class='card'><h2>階段總覽</h2><table><thead><tr>")
                .append("<th>階段順序</th><th>階段ID</th><th>名稱</th><th>目標</th><th>狀態</th>")
                .append("</tr></thead><tbody>");
        for (PlanPhase p : phases) {
            sb.append("<tr>")
                    .append("<td>").append(p.getPhaseOrder() == null ? "" : p.getPhaseOrder()).append("</td>")
                    .append("<td>").append(escapeHtml(p.getPhaseId())).append("</td>")
                    .append("<td>").append(escapeHtml(p.getName())).append("</td>")
                    .append("<td>").append(escapeHtml(p.getObjective())).append("</td>")
                    .append("<td>").append(p.getStatus() == null ? "" : p.getStatus().getValue()).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div>");

        sb.append("<div class='card'><h2>節點明細</h2><table><thead><tr>")
                .append("<th>節點ID</th><th>階段ID</th><th>名稱</th><th>類型</th><th>狀態</th>")
                .append("<th>重試</th><th>耗時(ms)</th><th>失敗原因</th><th>輸出給下游</th>")
                .append("</tr></thead><tbody>");
        for (PlanNode n : nodes) {
            sb.append("<tr>")
                    .append("<td>").append(escapeHtml(n.getNodeId())).append("</td>")
                    .append("<td>").append(escapeHtml(n.getPhaseId())).append("</td>")
                    .append("<td>").append(escapeHtml(n.getName())).append("</td>")
                    .append("<td>").append(n.getNodeType() == null ? "" : n.getNodeType().getValue()).append("</td>")
                    .append("<td>").append(n.getStatus() == null ? "" : n.getStatus().getValue()).append("</td>")
                    .append("<td>").append(n.getRetryCount() == null ? 0 : n.getRetryCount())
                    .append("/").append(n.getMaxRetry() == null ? DEFAULT_MAX_RETRY : n.getMaxRetry()).append("</td>")
                    .append("<td>").append(n.getCostMs() == null ? 0 : n.getCostMs()).append("</td>")
                    .append("<td>").append(escapeHtml(n.getFailReason())).append("</td>")
                    .append("<td><pre style='white-space:pre-wrap;font-size:11px;'>")
                    .append(escapeHtml(toJsonQuiet(n.getOutputForNext())))
                    .append("</pre></td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div></body></html>");
        return sb.toString();
    }

    private boolean isError(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return true;
        try {
            JsonNode node = objectMapper.readTree(jsonText);
            if (node.has("status")) {
                String s = node.get("status").asText("");
                return "error".equalsIgnoreCase(s) || "failed".equalsIgnoreCase(s);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Object safeParse(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return text;
        }
    }

    private Map<String, Object> extractDataPayload(String toolJsonResult) {
        try {
            JsonNode n = objectMapper.readTree(toolJsonResult);
            if (n.has("data")) return objectMapper.convertValue(n.get("data"), new TypeReference<>() {});
            return objectMapper.convertValue(n, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String extractErrorMessage(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(jsonText);
            if (node.has("message")) return node.get("message").asText("");
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String extractErrorCode(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(jsonText);
            if (node.has("errorCode")) return node.get("errorCode").asText("");
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private Object extractDataObj(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(jsonText);
            if (node.has("data")) {
                return objectMapper.convertValue(node.get("data"), new TypeReference<>() {});
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String asText(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String resolveCapabilityType(Map<String, Object> nodeDef) {
        String explicit = firstNonBlank(
                text(nodeDef.get("capabilityType")),
                text(nodeDef.get("capability_type"))
        );
        if (!explicit.isBlank()) {
            return normalizeCapabilityType(explicit);
        }
        return normalizeCapabilityType(text(nodeDef.get("nodeType")));
    }

    private String resolveCapabilityName(Map<String, Object> nodeDef) {
        String capabilityName = firstNonBlank(
                text(nodeDef.get("capabilityName")),
                text(nodeDef.get("capability_name")),
                text(nodeDef.get("toolName")),
                text(nodeDef.get("tool_name")),
                text(nodeDef.get("promptName")),
                text(nodeDef.get("prompt_name")),
                text(nodeDef.get("resourceUri")),
                text(nodeDef.get("resource_uri")),
                text(nodeDef.get("workflowName")),
                text(nodeDef.get("workflow_name")),
                text(nodeDef.get("name"))
        );
        return capabilityName;
    }

    private String resolveServerCode(Map<String, Object> nodeDef) {
        return firstNonBlank(
                text(nodeDef.get("serverCode")),
                text(nodeDef.get("server_code")),
                "local-agent-server"
        );
    }

    private Map<String, Object> resolveResolvedInputJson(Map<String, Object> nodeDef) {
        Map<String, Object> resolved = asMap(nodeDef.get("resolvedInputJson"));
        if (resolved != null) {
            return resolved;
        }
        resolved = asMap(nodeDef.get("resolved_input_json"));
        if (resolved != null) {
            return resolved;
        }
        return asMap(nodeDef.get("inputJson"));
    }

    private Boolean resolveApprovalRequired(Map<String, Object> nodeDef) {
        Object raw = nodeDef.containsKey("approvalRequired")
                ? nodeDef.get("approvalRequired")
                : nodeDef.get("approval_required");
        return boolVal(raw, false);
    }

    private String resolveApprovalStatus(Map<String, Object> nodeDef) {
        String explicit = firstNonBlank(
                text(nodeDef.get("approvalStatus")),
                text(nodeDef.get("approval_status"))
        );
        if (!explicit.isBlank()) {
            return explicit;
        }
        return resolveApprovalRequired(nodeDef) ? "PENDING" : "NOT_REQUIRED";
    }

    private String normalizeCapabilityType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "TOOL";
        }
        String normalized = rawType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TOOL", "PROMPT", "RESOURCE", "WORKFLOW", "ANALYZE", "VALIDATE", "REPORT", "CODE" -> normalized;
            default -> "TOOL";
        };
    }

    private PlanInputContext reconstructPlanInput(String sessionId, String userGoal) {
        try {
            StructuredContextPackage contextPackage = contextCompilerService.compile(sessionId, userGoal, null, null);
            InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                    sessionId,
                    userGoal,
                    contextPackage,
                    contextPackage == null ? null : contextPackage.getTaskState(),
                    contextPackage == null ? null : contextPackage.getRelationalState()
            );
            return new PlanInputContext(contextPackage, reconstructionResult);
        } catch (Exception ex) {
            log.warn("[Plan] input reconstruction failed, fallback to raw userGoal, sessionId={}, err={}", sessionId, ex.getMessage());
            return new PlanInputContext(null, null);
        }
    }

    private List<Map<String, Object>> extractPlanningKnowledgeEvidence(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return List.of();
        }
        List<Map<String, Object>> taskKnowledge = asListOfMap(contextPackage.getTaskContext().get("knowledge"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : taskKnowledge) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", firstNonBlank(text(item.get("chunk_id")), text(item.get("id"))));
            row.put("title", firstNonBlank(text(item.get("title")), text(item.get("chunk_summary"))));
            row.put("content", firstNonBlank(text(item.get("chunk_text")), text(item.get("content"))));
            row.put("source", firstNonBlank(text(item.get("source_type")), "RAG_KNOWLEDGE"));
            row.put("score", firstNonBlank(text(item.get("score")), text(item.get("similarity"))));
            out.add(row);
            if (out.size() >= 12) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> extractPlanningWorkflowHints(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getCapabilityCandidates() == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : contextPackage.getCapabilityCandidates()) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            String capabilityType = text(item.get("capability_type")).toUpperCase(Locale.ROOT);
            if (!"WORKFLOW".equals(capabilityType) && !"PROMPT".equals(capabilityType) && !"RESOURCE".equals(capabilityType)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("capabilityName", firstNonBlank(text(item.get("capability_name")), text(item.get("name"))));
            row.put("capabilityType", capabilityType);
            row.put("description", text(item.get("description")));
            row.put("serverCode", text(item.get("server_code")));
            row.put("requiresApproval", boolVal(item.get("requires_approval"), false));
            row.put("sensitivity", firstNonBlank(text(item.get("sensitivity")), "LOW"));
            out.add(row);
            if (out.size() >= 16) {
                break;
            }
        }
        return out;
    }

    private String resolveEffectiveGoal(String rawUserGoal,
                                        PlanningIntent planningIntent,
                                        InputReconstructionResult reconstructionResult) {
        String reconstructedGoal = reconstructionResult == null ? "" : text(reconstructionResult.getExplicitTaskGoal());
        if (!reconstructedGoal.isBlank()) {
            return reconstructedGoal;
        }
        String planningIntentGoal = planningIntent == null ? "" : text(planningIntent.goal());
        if (!planningIntentGoal.isBlank()) {
            return planningIntentGoal;
        }
        return rawUserGoal == null ? "" : rawUserGoal;
    }

    private PlanningIntent parsePlanningIntent(String userGoal) {
        String raw = userGoal == null ? "" : userGoal.trim();
        if (raw.isBlank()) {
            return new PlanningIntent("", Map.of());
        }
        String[] segments = raw.split("\\|");
        String resolvedGoal = raw;
        Map<String, String> meta = new LinkedHashMap<>();
        for (String segment : segments) {
            String item = segment == null ? "" : segment.trim();
            if (item.isBlank()) {
                continue;
            }
            int idx = item.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = item.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = item.substring(idx + 1).trim();
            if (value.isBlank()) {
                continue;
            }
            meta.put(key, value);
            if ("task_goal".equals(key) || "goal".equals(key) || "explicit_task_goal".equals(key)) {
                resolvedGoal = value;
            }
        }
        return new PlanningIntent(resolvedGoal, meta);
    }

    private int intVal(Object o, int def) {
        try {
            if (o == null) return def;
            if (o instanceof Number num) return num.intValue();
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean boolVal(Object o, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String t = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t)) {
            return true;
        }
        if ("false".equals(t) || "0".equals(t) || "no".equals(t)) {
            return false;
        }
        return def;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String toJsonQuiet(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String error(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", code == null ? "" : code,
                    "message", message == null ? "" : message
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"errorCode\":\"" + code + "\",\"message\":\"" + message + "\"}";
        }
    }

    private String normalizeScopedId(String planId, String rawId) {
        String rid = rawId == null ? "" : rawId.trim();
        if (rid.isBlank()) return planId + ":" + SnowflakeIdUtil.nextIdStr();
        if (rid.startsWith(planId + ":")) return rid;
        return planId + ":" + rid;
    }

    private String resolveMappedOrScopedId(Map<String, String> idMap, String planId, String rawId) {
        String v = rawId == null ? "" : rawId.trim();
        if (v.isBlank()) return normalizeScopedId(planId, SnowflakeIdUtil.nextIdStr());
        if (idMap != null && idMap.containsKey(v)) return idMap.get(v);
        return normalizeScopedId(planId, v);
    }

    private List<String> remapStringListIds(List<String> rawIds, Map<String, String> idMap, String planId) {
        if (rawIds == null || rawIds.isEmpty()) return rawIds;
        return rawIds.stream()
                .map(raw -> resolveMappedOrScopedId(idMap, planId, raw))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMap(Object obj) {
        if (obj == null) return Collections.emptyList();
        try {
            return objectMapper.convertValue(obj, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> asMap(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.convertValue(obj, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> asStringMap(Object obj) {
        if (obj == null) return Collections.emptyMap();
        try {
            Map<String, Object> raw = objectMapper.convertValue(obj, new TypeReference<>() {});
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<String> asStringList(Object obj) {
        if (obj == null) return null;
        try {
            List<Object> raw = objectMapper.convertValue(obj, new TypeReference<>() {});
            return raw.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return null;
        }
    }

    private PlanNodeType parseNodeType(String text) {
        if (text == null || text.isBlank()) return PlanNodeType.TOOL;
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        try {
            return PlanNodeType.valueOf(normalized);
        } catch (Exception e) {
            return PlanNodeType.TOOL;
        }
    }

    private PlanRiskLevel parseRiskLevel(String text) {
        if (text == null || text.isBlank()) return PlanRiskLevel.LOW;
        try {
            return PlanRiskLevel.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return PlanRiskLevel.LOW;
        }
    }

    private PlanModelHint parseModelHint(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return PlanModelHint.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private record PlanningIntent(String goal, Map<String, String> meta) {
    }

    private record PlanInputContext(StructuredContextPackage contextPackage,
                                    InputReconstructionResult reconstructionResult) {
    }
}
