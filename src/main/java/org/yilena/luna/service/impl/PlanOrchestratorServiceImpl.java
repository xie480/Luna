package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.PlanEdge;
import org.yilena.luna.entity.PlanInstance;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.entity.PlanPhase;
import org.yilena.luna.enums.*;
import org.yilena.luna.mapper.PlanEdgeMapper;
import org.yilena.luna.mapper.PlanInstanceMapper;
import org.yilena.luna.mapper.PlanNodeMapper;
import org.yilena.luna.mapper.PlanPhaseMapper;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.BlueprintValidationService;
import org.yilena.luna.service.MasterPlanningService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.tools.PlanBlueprintTools;
import org.yilena.luna.tools.PlanEventTools;
import org.yilena.luna.tools.PlanNodeTools;
import org.yilena.luna.tools.PlanReportTools;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenClaw 计划编排服务实现（Master Planner 版）
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

    private final MasterPlanningService masterPlanningService;
    private final BlueprintValidationService blueprintValidationService;
    private final AgentService agentService;

    @Override
    public String createAndRunPlan(String sessionId, String userGoal) {
        String planId = null;
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "sessionId 不能为空");
            }
            if (userGoal == null || userGoal.isBlank()) {
                return error("PLAN_INVALID_INPUT", "userGoal 不能为空");
            }

            planId = "plan-" + SnowflakeIdUtil.nextIdStr();
            int planVersion = 1;

            PlanInstance instance = PlanInstance.builder()
                    .planId(planId)
                    .sessionId(sessionId)
                    .userGoal(userGoal)
                    .planVersion(planVersion)
                    .status(PlanStatus.PENDING)
                    .currentLoopIndex(0)
                    .planningModel("master-planner-code")
                    .startedAt(LocalDateTime.now())
                    .build();
            planInstanceMapper.insert(instance);

            emitPlanCreated(planId, sessionId, userGoal, planVersion);

            Map<String, Object> blueprint = masterPlanningService.generateBlueprint(planId, sessionId, userGoal);
            String validateErr = blueprintValidationService.validate(blueprint);
            if (validateErr != null) {
                updatePlanStatus(planId, PlanStatus.FAILED, validateErr);
                emitPlanFinished(planId, "FAILED", validateErr);
                return error("PLAN_BLUEPRINT_INVALID", validateErr);
            }

            String saveResult = planBlueprintTools.savePlanBlueprint(
                    planId,
                    planVersion,
                    objectMapper.writeValueAsString(blueprint),
                    "master-planner-code",
                    LocalDateTime.now().toString()
            );
            if (isError(saveResult)) {
                markPlanFailed(planId, "保存蓝图失败");
                emitPlanFinished(planId, "FAILED", "保存蓝图失败");
                return saveResult;
            }

            materializePhasesAndNodes(planId, blueprint);
            buildEdgesFromBlueprint(planId, blueprint);

            updatePlanStatus(planId, PlanStatus.RUNNING, null);

            List<PlanPhase> orderedPhases = loadOrderedPhases(planId);
            if (orderedPhases.isEmpty()) {
                markPlanFailed(planId, "未找到可执行阶段");
                emitPlanFinished(planId, "FAILED", "未找到可执行阶段");
                return error("PLAN_PHASE_EMPTY", "未找到可执行阶段");
            }

            List<Map<String, Object>> phaseResults = new ArrayList<>();
            boolean hasPhaseFailure = false;

            for (PlanPhase phase : orderedPhases) {
                String phaseId = phase.getPhaseId();
                markPhaseStatus(phase, PlanPhaseStatus.RUNNING, true, false);

                String phaseResult = runPhase(planId, phaseId);
                phaseResults.add(Map.of(
                        "phaseId", phaseId,
                        "phaseOrder", phase.getPhaseOrder() == null ? 0 : phase.getPhaseOrder(),
                        "result", safeParse(phaseResult)
                ));

                if (isError(phaseResult)) {
                    hasPhaseFailure = true;
                    markPhaseStatus(phase, PlanPhaseStatus.FAILED, false, true);
                    break;
                } else {
                    markPhaseStatus(phase, PlanPhaseStatus.SUCCESS, false, true);
                }
            }

            String reportResult = finalizeAndReport(planId);

            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("planId", planId);
            merged.put("phaseResults", phaseResults);
            merged.put("reportResult", safeParse(reportResult));

            if (hasPhaseFailure) {
                updatePlanStatus(planId, PlanStatus.FAILED, "阶段执行失败");
                emitPlanFinished(planId, "FAILED", "阶段执行失败");
                merged.put("status", "error");
                merged.put("message", "计划阶段执行失败，已生成报告");
                return objectMapper.writeValueAsString(merged);
            }

            if (isError(reportResult)) {
                updatePlanStatus(planId, PlanStatus.FAILED, "报告生成失败");
                emitPlanFinished(planId, "FAILED", "报告生成失败");
                merged.put("status", "error");
                merged.put("message", "计划执行完成，但报告生成失败");
                return objectMapper.writeValueAsString(merged);
            }

            updatePlanStatus(planId, PlanStatus.SUCCESS, null);
            emitPlanFinished(planId, "SUCCESS", "计划多阶段执行成功并生成报告");
            merged.put("status", "success");
            merged.put("message", "计划多阶段执行成功并生成报告");
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.error("createAndRunPlan 失败", e);
            if (planId != null && !planId.isBlank()) {
                updatePlanStatus(planId, PlanStatus.FAILED, "创建并执行计划失败: " + e.getMessage());
                emitPlanFinished(planId, "FAILED", "创建并执行计划失败: " + e.getMessage());
            }
            return error("PLAN_CREATE_RUN_FAILED", "创建并执行计划失败: " + e.getMessage());
        }
    }

    @Override
    public String runPhase(String planId, String phaseId) {
        try {
            if (planId == null || planId.isBlank() || phaseId == null || phaseId.isBlank()) {
                return error("PHASE_INVALID_INPUT", "planId 和 phaseId 不能为空");
            }

            String sessionId = resolveSessionIdByPlanId(planId);

            String listResult = planNodeTools.listPhaseNodes(planId, phaseId);
            if (isError(listResult)) {
                return listResult;
            }

            List<PlanNode> nodes = planNodeMapper.selectList(
                    new LambdaQueryWrapper<PlanNode>()
                            .eq(PlanNode::getPlanId, planId)
                            .eq(PlanNode::getPhaseId, phaseId)
                            .orderByAsc(PlanNode::getNodeId)
            );

            if (nodes.isEmpty()) {
                return error("PHASE_EMPTY", "阶段下无可执行节点");
            }

            long phaseStart = System.currentTimeMillis();
            int successCount = 0;
            int failCount = 0;
            int phaseOrder = phaseOrderOf(planId, phaseId);

            emitPlanEvent(
                    "PLAN_PHASE_STARTED",
                    "INFO",
                    planId,
                    phaseId,
                    "",
                    Map.of(
                            "eventType", "PLAN_PHASE_STARTED",
                            "planId", planId,
                            "phaseId", phaseId,
                            "nodeId", "",
                            "status", "RUNNING",
                            "message", "阶段开始执行",
                            "phaseOrder", phaseOrder,
                            "successCount", 0,
                            "failCount", 0,
                            "timestamp", System.currentTimeMillis()
                    )
            );

            for (PlanNode node : nodes) {
                long nodeStart = System.currentTimeMillis();
                String nodeId = node.getNodeId();
                String skillName = node.getName() == null ? "" : node.getName();
                String nodeType = node.getNodeType() == null ? "" : node.getNodeType().getValue();
                int retryCount = node.getRetryCount() == null ? 0 : node.getRetryCount();
                int maxRetry = node.getMaxRetry() == null ? DEFAULT_MAX_RETRY : node.getMaxRetry();

                if (!canTransitToRunning(node.getStatus())) {
                    failCount++;
                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "WARN",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "节点状态流转不合法", skillName, nodeType,
                                    "非法状态流转", "NODE_INVALID_TRANSITION",
                                    retryCount, 0L, Map.of(), System.currentTimeMillis()
                            )
                    );
                    continue;
                }

                String running = planNodeTools.updateNodeStatus(
                        planId, nodeId, "RUNNING", null, null, retryCount
                );
                if (isError(running)) {
                    failCount++;
                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "ERROR",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "更新节点运行状态失败", skillName, nodeType,
                                    "update_node_status RUNNING failed", "NODE_RUNNING_UPDATE_FAILED",
                                    retryCount, System.currentTimeMillis() - nodeStart, Map.of(), System.currentTimeMillis()
                            )
                    );
                    continue;
                }

                emitPlanEvent(
                        "PLAN_NODE_RUNNING",
                        "INFO",
                        planId,
                        phaseId,
                        nodeId,
                        buildNodeEventPayload(
                                "PLAN_NODE_RUNNING", planId, phaseId, nodeId, "RUNNING",
                                "节点执行中", skillName, nodeType,
                                "", "", retryCount, 0L, Map.of(), System.currentTimeMillis()
                        )
                );

                String nodeGoal = buildNodeGoal(planId, phaseId, node);
                String agentResult = agentService.processToolCalling(sessionId, nodeGoal);

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("nodeName", node.getName());
                output.put("phaseId", phaseId);
                output.put("nodeGoal", nodeGoal);
                output.put("agentResult", safeParse(agentResult));
                output.put("result", isError(agentResult) ? "error" : "ok");

                Map<String, Object> outputForNext = new LinkedHashMap<>();
                outputForNext.put("nodeId", nodeId);
                outputForNext.put("result", isError(agentResult) ? "error" : "ok");
                outputForNext.put("agentResult", safeParse(agentResult));

                long nodeCostMs = System.currentTimeMillis() - nodeStart;

                if (!isError(agentResult)) {
                    String appendRet = planNodeTools.appendNodeOutput(
                            planId,
                            nodeId,
                            objectMapper.writeValueAsString(output),
                            objectMapper.writeValueAsString(outputForNext)
                    );
                    if (isError(appendRet)) {
                        failCount++;
                        planNodeTools.updateNodeStatus(planId, nodeId, "FAILED", nodeCostMs, "append_node_output failed", retryCount);
                        emitPlanEvent(
                                "PLAN_NODE_FAILED",
                                "ERROR",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                        "节点输出落库失败", skillName, nodeType,
                                        "append_node_output failed", "NODE_OUTPUT_APPEND_FAILED",
                                        retryCount, nodeCostMs, outputForNext, System.currentTimeMillis()
                                )
                        );
                        continue;
                    }

                    String successRet = planNodeTools.updateNodeStatus(
                            planId, nodeId, "SUCCESS", nodeCostMs, null, retryCount
                    );
                    if (isError(successRet)) {
                        failCount++;
                        emitPlanEvent(
                                "PLAN_NODE_FAILED",
                                "ERROR",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                        "更新节点成功状态失败", skillName, nodeType,
                                        "update_node_status SUCCESS failed", "NODE_SUCCESS_UPDATE_FAILED",
                                        retryCount, nodeCostMs, outputForNext, System.currentTimeMillis()
                                )
                        );
                    } else {
                        successCount++;
                        emitPlanEvent(
                                "PLAN_NODE_SUCCESS",
                                "INFO",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_SUCCESS", planId, phaseId, nodeId, "SUCCESS",
                                        "节点执行成功", skillName, nodeType,
                                        "", "", retryCount, nodeCostMs, outputForNext, System.currentTimeMillis()
                                )
                        );
                    }
                    continue;
                }

                // 失败路径：按最大重试次数处理
                String failReason = extractErrorMessage(agentResult);
                String failCode = extractErrorCode(agentResult);

                boolean recovered = false;
                for (int r = retryCount + 1; r <= maxRetry; r++) {
                    String retryRun = planNodeTools.updateNodeStatus(planId, nodeId, "RUNNING", null, null, r);
                    if (isError(retryRun)) {
                        break;
                    }
                    String retryResult = agentService.processToolCalling(sessionId, nodeGoal);
                    if (!isError(retryResult)) {
                        Map<String, Object> retryOutput = new LinkedHashMap<>(output);
                        retryOutput.put("agentResult", safeParse(retryResult));
                        retryOutput.put("result", "ok");
                        Map<String, Object> retryOutputForNext = new LinkedHashMap<>(outputForNext);
                        retryOutputForNext.put("agentResult", safeParse(retryResult));
                        retryOutputForNext.put("result", "ok");

                        planNodeTools.appendNodeOutput(
                                planId,
                                nodeId,
                                objectMapper.writeValueAsString(retryOutput),
                                objectMapper.writeValueAsString(retryOutputForNext)
                        );
                        planNodeTools.updateNodeStatus(
                                planId, nodeId, "SUCCESS", System.currentTimeMillis() - nodeStart, null, r
                        );

                        successCount++;
                        recovered = true;
                        emitPlanEvent(
                                "PLAN_NODE_SUCCESS",
                                "INFO",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_SUCCESS", planId, phaseId, nodeId, "SUCCESS",
                                        "节点重试后成功", skillName, nodeType,
                                        "", "", r, System.currentTimeMillis() - nodeStart, retryOutputForNext, System.currentTimeMillis()
                                )
                        );
                        break;
                    } else {
                        failReason = extractErrorMessage(retryResult);
                        failCode = extractErrorCode(retryResult);
                    }
                }

                if (!recovered) {
                    failCount++;
                    planNodeTools.updateNodeStatus(
                            planId, nodeId, "FAILED", System.currentTimeMillis() - nodeStart, failReason, maxRetry
                    );
                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "WARN",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "节点执行失败", skillName, nodeType,
                                    failReason, failCode,
                                    maxRetry, System.currentTimeMillis() - nodeStart, outputForNext, System.currentTimeMillis()
                            )
                    );
                }
            }

            long phaseCostMs = System.currentTimeMillis() - phaseStart;
            String phaseStatus = failCount > 0 ? "FAILED" : "SUCCESS";

            emitPlanEvent(
                    "PLAN_PHASE_FINISHED",
                    failCount > 0 ? "WARN" : "INFO",
                    planId,
                    phaseId,
                    "",
                    Map.of(
                            "eventType", "PLAN_PHASE_FINISHED",
                            "planId", planId,
                            "phaseId", phaseId,
                            "nodeId", "",
                            "status", phaseStatus,
                            "message", failCount > 0 ? "阶段执行完成（含失败节点）" : "阶段执行成功",
                            "phaseOrder", phaseOrder,
                            "successCount", successCount,
                            "failCount", failCount,
                            "costMs", phaseCostMs,
                            "timestamp", System.currentTimeMillis()
                    )
            );

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", failCount > 0 ? "error" : "success");
            out.put("planId", planId);
            out.put("phaseId", phaseId);
            out.put("phaseOrder", phaseOrder);
            out.put("successCount", successCount);
            out.put("failCount", failCount);
            out.put("costMs", phaseCostMs);
            out.put("message", failCount > 0 ? "阶段执行存在失败节点" : "阶段执行成功");

            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("runPhase 失败, planId={}, phaseId={}", planId, phaseId, e);
            return error("PHASE_EXECUTION_FAILED", "阶段执行失败: " + e.getMessage());
        }
    }

    @Override
    public String finalizeAndReport(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能为空");
            }

            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) {
                return error("PLAN_NOT_FOUND", "计划不存在");
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

            long total = nodes.size();
            long success = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SUCCESS).count();
            long failed = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.FAILED).count();
            long skipped = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SKIPPED).count();

            String finalStatusText;
            PlanFinalStatus finalStatus;
            if (failed == 0 && success > 0) {
                finalStatusText = "SUCCESS";
                finalStatus = PlanFinalStatus.SUCCESS;
            } else if (success > 0) {
                finalStatusText = "PARTIAL";
                finalStatus = PlanFinalStatus.PARTIAL;
            } else {
                finalStatusText = "FAILED";
                finalStatus = PlanFinalStatus.FAILED;
            }

            String html = buildReportHtml(instance, phases, nodes, finalStatusText);

            String fileName = planId + ".html";
            String writeResult = planReportTools.writeHtmlReportFile(
                    planId,
                    html,
                    fileName,
                    "./data/reports"
            );
            if (isError(writeResult)) {
                return writeResult;
            }

            Map<String, Object> writePayload = extractDataPayload(writeResult);
            String reportPath = asText(writePayload.get("reportPath"));
            String reportUrl = asText(writePayload.get("reportUrl"));

            String openResult = planReportTools.openBrowserWithFile(reportPath);
            Map<String, Object> openPayload = extractDataPayload(openResult);
            String openFlag = asText(openPayload.getOrDefault("openResult", "FAILED"));

            // 更新 plan_instance 最终状态和结束时间
            instance.setFinalStatus(finalStatus);
            instance.setFinishedAt(LocalDateTime.now());
            if (PlanFinalStatus.SUCCESS.equals(finalStatus)) {
                instance.setStatus(PlanStatus.SUCCESS);
                instance.setErrorMessage(null);
            } else {
                instance.setStatus(PlanStatus.FAILED);
                if (instance.getErrorMessage() == null || instance.getErrorMessage().isBlank()) {
                    instance.setErrorMessage("计划执行存在失败节点");
                }
            }
            planInstanceMapper.updateById(instance);

            emitPlanEvent(
                    "PLAN_REPORT_READY",
                    "INFO",
                    planId,
                    "",
                    "",
                    Map.of(
                            "eventType", "PLAN_REPORT_READY",
                            "planId", planId,
                            "phaseId", "",
                            "nodeId", "",
                            "status", "SUCCESS",
                            "message", "任务报告已生成",
                            "reportPath", reportPath,
                            "reportUrl", reportUrl,
                            "openResult", openFlag,
                            "timestamp", System.currentTimeMillis()
                    )
            );

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("planId", planId);
            out.put("finalStatus", finalStatusText);
            out.put("reportPath", reportPath);
            out.put("reportUrl", reportUrl);
            out.put("openResult", openFlag);
            out.put("nodeTotal", total);
            out.put("nodeSuccess", success);
            out.put("nodeFailed", failed);
            out.put("nodeSkipped", skipped);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("finalizeAndReport 失败, planId={}", planId, e);
            return error("PLAN_REPORT_FAILED", "收尾与报告生成失败: " + e.getMessage());
        }
    }

    @Override
    public String getPlanGraph(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能为空");
            }

            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) {
                return error("PLAN_NOT_FOUND", "计划不存在");
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

            List<Map<String, Object>> phaseList = phases.stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("phaseId", p.getPhaseId());
                m.put("phaseOrder", p.getPhaseOrder());
                m.put("name", p.getName());
                m.put("objective", p.getObjective());
                m.put("status", p.getStatus() != null ? p.getStatus().getValue() : "");
                return m;
            }).toList();

            List<Map<String, Object>> nodeList = nodes.stream().map(n -> {
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
                m.put("outputForNext", n.getOutputForNext() == null ? Map.of() : n.getOutputForNext());
                return m;
            }).toList();

            List<Map<String, Object>> edgeList = edges.stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fromNodeId", e.getFromNodeId());
                m.put("toNodeId", e.getToNodeId());
                m.put("conditionExpr", e.getConditionExpr() == null ? "" : e.getConditionExpr());
                return m;
            }).toList();

            graph.put("phases", phaseList);
            graph.put("nodes", nodeList);
            graph.put("edges", edgeList);

            Map<String, Long> nodeStats = nodes.stream()
                    .collect(Collectors.groupingBy(
                            n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(),
                            Collectors.counting()
                    ));
            graph.put("nodeStats", nodeStats);

            return objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            log.error("getPlanGraph 失败, planId={}", planId, e);
            return error("PLAN_GRAPH_FAILED", "获取计划图谱失败: " + e.getMessage());
        }
    }

    private void materializePhasesAndNodes(String planId, Map<String, Object> blueprint) throws Exception {
        List<Map<String, Object>> phaseDefs = asListOfMap(blueprint.get("phases"));
        List<Map<String, Object>> nodeDefs = asListOfMap(blueprint.get("nodes"));

        Map<String, List<String>> phaseNodeIds = new LinkedHashMap<>();

        for (Map<String, Object> p : phaseDefs) {
            String phaseId = text(p.get("phaseId"));
            if (phaseId.isBlank()) {
                phaseId = planId + ":phase-" + (intVal(p.get("phaseOrder"), 1));
            }

            PlanPhase phase = PlanPhase.builder()
                    .phaseId(phaseId)
                    .planId(planId)
                    .phaseOrder(intVal(p.get("phaseOrder"), 1))
                    .name(text(p.get("name")))
                    .objective(text(p.get("objective")))
                    .entryCriteria(text(p.get("entryCriteria")))
                    .exitCriteria(text(p.get("exitCriteria")))
                    .status(PlanPhaseStatus.PENDING)
                    .build();
            planPhaseMapper.insert(phase);
            phaseNodeIds.put(phaseId, new ArrayList<>());
        }

        for (Map<String, Object> n : nodeDefs) {
            String nodeId = text(n.get("nodeId"));
            if (nodeId.isBlank()) {
                nodeId = "node-" + SnowflakeIdUtil.nextIdStr();
            }

            String phaseId = text(n.get("phaseId"));
            if (phaseId.isBlank() || !phaseNodeIds.containsKey(phaseId)) {
                // 回退到第一个 phase
                phaseId = phaseNodeIds.keySet().stream().findFirst().orElse(planId + ":phase-1");
            }

            String nodeTypeStr = text(n.get("nodeType"));
            PlanNodeType nodeType = parseNodeType(nodeTypeStr);

            PlanNode node = PlanNode.builder()
                    .nodeId(nodeId)
                    .planId(planId)
                    .phaseId(phaseId)
                    .name(text(n.get("name")))
                    .nodeType(nodeType)
                    .inputJson(asMap(n.get("inputJson")))
                    .expectedOutputSchema(asMap(n.get("expectedOutputSchema")))
                    .dependencies(asStringList(n.get("dependencies")))
                    .parallelGroup(text(n.get("parallelGroup")))
                    .status(PlanNodeStatus.PENDING)
                    .retryPolicy(asMap(n.get("retryPolicy")))
                    .retryCount(intVal(n.get("retryCount"), 0))
                    .maxRetry(intVal(n.get("maxRetry"), DEFAULT_MAX_RETRY))
                    .modelHint(parseModelHint(text(n.get("modelHint"))))
                    .resourceHint(asMap(n.get("resourceHint")))
                    .riskLevel(parseRiskLevel(text(n.get("riskLevel"))))
                    .build();

            planNodeMapper.insert(node);
            phaseNodeIds.computeIfAbsent(phaseId, k -> new ArrayList<>()).add(nodeId);
        }

        // 回写 phase.nodeIds
        for (Map.Entry<String, List<String>> e : phaseNodeIds.entrySet()) {
            PlanPhase phase = planPhaseMapper.selectById(e.getKey());
            if (phase != null) {
                phase.setNodeIds(e.getValue());
                planPhaseMapper.updateById(phase);
            }
        }
    }

    private void buildEdgesFromBlueprint(String planId, Map<String, Object> blueprint) {
        try {
            List<Map<String, Object>> edges = asListOfMap(blueprint.get("edges"));
            for (Map<String, Object> e : edges) {
                String from = text(e.get("fromNodeId"));
                String to = text(e.get("toNodeId"));
                if (from.isBlank() || to.isBlank()) {
                    continue;
                }

                PlanEdge edge = PlanEdge.builder()
                        .planId(planId)
                        .fromNodeId(from)
                        .toNodeId(to)
                        .conditionExpr(text(e.get("conditionExpr")))
                        .build();

                // 去重
                long exists = planEdgeMapper.selectCount(
                        new LambdaQueryWrapper<PlanEdge>()
                                .eq(PlanEdge::getPlanId, planId)
                                .eq(PlanEdge::getFromNodeId, from)
                                .eq(PlanEdge::getToNodeId, to)
                );
                if (exists == 0) {
                    planEdgeMapper.insert(edge);
                }
            }
        } catch (Exception ex) {
            log.warn("buildEdgesFromBlueprint 失败，忽略并继续, planId={}, err={}", planId, ex.getMessage());
        }
    }

    private List<PlanPhase> loadOrderedPhases(String planId) {
        return planPhaseMapper.selectList(
                new LambdaQueryWrapper<PlanPhase>()
                        .eq(PlanPhase::getPlanId, planId)
                        .orderByAsc(PlanPhase::getPhaseOrder)
        );
    }

    private void markPhaseStatus(PlanPhase phase, PlanPhaseStatus status, boolean markStart, boolean markFinish) {
        if (phase == null) return;
        phase.setStatus(status);
        if (markStart && phase.getStartedAt() == null) {
            phase.setStartedAt(LocalDateTime.now());
        }
        if (markFinish) {
            phase.setFinishedAt(LocalDateTime.now());
        }
        planPhaseMapper.updateById(phase);
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
    }

    private void markPlanFailed(String planId, String reason) {
        updatePlanStatus(planId, PlanStatus.FAILED, reason);
    }

    private String resolveSessionIdByPlanId(String planId) {
        PlanInstance p = planInstanceMapper.selectById(planId);
        if (p == null || p.getSessionId() == null || p.getSessionId().isBlank()) {
            return "plan-default-session";
        }
        return p.getSessionId();
    }

    private boolean canTransitToRunning(PlanNodeStatus status) {
        if (status == null) return true;
        return status == PlanNodeStatus.PENDING || status == PlanNodeStatus.BLOCKED || status == PlanNodeStatus.APPROVAL_PENDING;
    }

    private String buildNodeGoal(String planId, String phaseId, PlanNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("计划ID=").append(planId)
                .append("；阶段ID=").append(phaseId)
                .append("；节点ID=").append(node.getNodeId())
                .append("；节点名称=").append(node.getName() == null ? "" : node.getName())
                .append("；节点类型=").append(node.getNodeType() == null ? "" : node.getNodeType().getValue());

        if (node.getInputJson() != null && !node.getInputJson().isEmpty()) {
            sb.append("；输入=").append(toJsonQuiet(node.getInputJson()));
        }
        if (node.getResourceHint() != null && !node.getResourceHint().isEmpty()) {
            sb.append("；资源提示=").append(toJsonQuiet(node.getResourceHint()));
        }
        if (node.getExpectedOutputSchema() != null && !node.getExpectedOutputSchema().isEmpty()) {
            sb.append("；期望输出Schema=").append(toJsonQuiet(node.getExpectedOutputSchema()));
        }
        return sb.toString();
    }

    private void emitPlanCreated(String planId, String sessionId, String userGoal, int planVersion) {
        emitPlanEvent(
                "PLAN_CREATED",
                "INFO",
                planId,
                "",
                "",
                Map.of(
                        "eventType", "PLAN_CREATED",
                        "planId", planId,
                        "phaseId", "",
                        "nodeId", "",
                        "status", "PENDING",
                        "message", "计划已创建",
                        "sessionId", sessionId,
                        "userGoal", userGoal,
                        "planVersion", planVersion,
                        "timestamp", System.currentTimeMillis()
                )
        );
    }

    private void emitPlanFinished(String planId, String finalStatus, String message) {
        emitPlanEvent(
                "PLAN_FINISHED",
                "INFO",
                planId,
                "",
                "",
                Map.of(
                        "eventType", "PLAN_FINISHED",
                        "planId", planId,
                        "phaseId", "",
                        "nodeId", "",
                        "status", finalStatus,
                        "message", message == null ? "" : message,
                        "timestamp", System.currentTimeMillis()
                )
        );
    }

    private void emitPlanEvent(String eventType, String level, String planId, String phaseId, String nodeId, Map<String, Object> payload) {
        try {
            planEventTools.emitPlanEvent(
                    "default",
                    planId == null ? "" : planId,
                    phaseId == null ? "" : phaseId,
                    nodeId == null ? "" : nodeId,
                    level == null ? "INFO" : level,
                    eventType == null ? "PLAN_REPORT_READY" : eventType,
                    objectMapper.writeValueAsString(payload == null ? Map.of() : payload),
                    UUID.randomUUID().toString()
            );
        } catch (Exception e) {
            log.warn("emitPlanEvent 失败但不阻断主流程, planId={}, eventType={}, err={}", planId, eventType, e.getMessage());
        }
    }

    private Map<String, Object> buildNodeEventPayload(
            String eventType,
            String planId,
            String phaseId,
            String nodeId,
            String status,
            String message,
            String skillName,
            String nodeType,
            String failReason,
            String errorCode,
            int retryCount,
            long costMs,
            Map<String, Object> outputForNext,
            long ts
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("planId", planId);
        payload.put("phaseId", phaseId);
        payload.put("nodeId", nodeId);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("skillName", skillName);
        payload.put("nodeType", nodeType);
        payload.put("failReason", failReason == null ? "" : failReason);
        payload.put("errorCode", errorCode == null ? "" : errorCode);
        payload.put("retryCount", retryCount);
        payload.put("costMs", costMs);
        payload.put("outputForNext", outputForNext == null ? Map.of() : outputForNext);
        payload.put("timestamp", ts);
        return payload;
    }

    private boolean isError(String jsonText) {
        JsonNode node = safeNode(jsonText);
        if (node == null) return false;
        if (node.has("status")) {
            String s = node.get("status").asText("");
            return "error".equalsIgnoreCase(s) || "failed".equalsIgnoreCase(s);
        }
        return false;
    }

    private Object safeParse(String text) {
        JsonNode node = safeNode(text);
        if (node != null) return node;
        return text == null ? "" : text;
    }

    private JsonNode safeNode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractErrorMessage(String result) {
        JsonNode n = safeNode(result);
        if (n == null) return "unknown error";
        if (n.has("message")) return n.get("message").asText("unknown error");
        if (n.has("error")) return n.get("error").asText("unknown error");
        return "unknown error";
    }

    private String extractErrorCode(String result) {
        JsonNode n = safeNode(result);
        if (n == null) return "UNKNOWN_ERROR";
        if (n.has("errorCode")) return n.get("errorCode").asText("UNKNOWN_ERROR");
        return "UNKNOWN_ERROR";
    }

    private int phaseOrderOf(String planId, String phaseId) {
        PlanPhase p = planPhaseMapper.selectById(phaseId);
        if (p == null || !Objects.equals(planId, p.getPlanId()) || p.getPhaseOrder() == null) return 0;
        return p.getPhaseOrder();
    }

    private String buildReportHtml(PlanInstance instance, List<PlanPhase> phases, List<PlanNode> nodes, String finalStatus) {
        long success = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SUCCESS).count();
        long failed = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.FAILED).count();
        long skipped = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SKIPPED).count();

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/>")
                .append("<title>OpenClaw 任务报告 - ").append(instance.getPlanId()).append("</title>")
                .append("<style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;padding:24px;background:#f7f8fa;color:#1f2937}")
                .append("h1,h2{margin:8px 0} .card{background:#fff;border-radius:10px;padding:16px;margin:12px 0;box-shadow:0 2px 8px rgba(0,0,0,.06)}")
                .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #e5e7eb;padding:8px;text-align:left;font-size:13px}")
                .append(".ok{color:#16a34a}.bad{color:#dc2626}.warn{color:#d97706}")
                .append("</style></head><body>");

        sb.append("<h1>OpenClaw 任务报告</h1>");
        sb.append("<div class='card'>")
                .append("<p><b>计划ID：</b>").append(escapeHtml(instance.getPlanId())).append("</p>")
                .append("<p><b>会话ID：</b>").append(escapeHtml(instance.getSessionId())).append("</p>")
                .append("<p><b>用户目标：</b>").append(escapeHtml(instance.getUserGoal())).append("</p>")
                .append("<p><b>最终状态：</b>").append(escapeHtml(finalStatus)).append("</p>")
                .append("<p><b>创建时间：</b>").append(instance.getCreatedAt() == null ? "" : instance.getCreatedAt()).append("</p>")
                .append("<p><b>结束时间：</b>").append(LocalDateTime.now()).append("</p>")
                .append("</div>");

        sb.append("<div class='card'><h2>节点统计</h2>")
                .append("<p>总节点：").append(nodes.size())
                .append("，<span class='ok'>成功：").append(success).append("</span>")
                .append("，<span class='bad'>失败：").append(failed).append("</span>")
                .append("，<span class='warn'>跳过：").append(skipped).append("</span></p>")
                .append("</div>");

        sb.append("<div class='card'><h2>阶段总览</h2><table><thead><tr>")
                .append("<th>阶段顺序</th><th>阶段ID</th><th>名称</th><th>目标</th><th>状态</th>")
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

        sb.append("<div class='card'><h2>节点明细</h2><table><thead><tr>")
                .append("<th>节点ID</th><th>阶段ID</th><th>名称</th><th>类型</th><th>状态</th><th>重试</th><th>耗时(ms)</th><th>失败原因</th><th>输出给下游</th>")
                .append("</tr></thead><tbody>");
        for (PlanNode n : nodes) {
            sb.append("<tr>")
                    .append("<td>").append(escapeHtml(n.getNodeId())).append("</td>")
                    .append("<td>").append(escapeHtml(n.getPhaseId())).append("</td>")
                    .append("<td>").append(escapeHtml(n.getName())).append("</td>")
                    .append("<td>").append(n.getNodeType() == null ? "" : n.getNodeType().getValue()).append("</td>")
                    .append("<td>").append(n.getStatus() == null ? "" : n.getStatus().getValue()).append("</td>")
                    .append("<td>").append(n.getRetryCount() == null ? 0 : n.getRetryCount()).append("/").append(n.getMaxRetry() == null ? DEFAULT_MAX_RETRY : n.getMaxRetry()).append("</td>")
                    .append("<td>").append(n.getCostMs() == null ? 0 : n.getCostMs()).append("</td>")
                    .append("<td>").append(escapeHtml(n.getFailReason())).append("</td>")
                    .append("<td><pre style='white-space:pre-wrap;'>").append(escapeHtml(toJsonQuiet(n.getOutputForNext()))).append("</pre></td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private String toJsonQuiet(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String error(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", code == null ? "" : code,
                    "message", message == null ? "" : message
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"errorCode\":\"" + (code == null ? "" : code) + "\",\"message\":\"" + (message == null ? "" : message.replace("\"", "\\\"")) + "\"}";
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private List<Map<String, Object>> asListOfMap(Object obj) {
        if (obj == null) return Collections.emptyList();
        try {
            return objectMapper.convertValue(obj, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> asMap(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.convertValue(obj, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> asStringList(Object obj) {
        if (obj == null) return null;
        try {
            List<Object> raw = objectMapper.convertValue(obj, new TypeReference<List<Object>>() {});
            return raw.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return null;
        }
    }

    private String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String asText(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private int intVal(Object o, int def) {
        try {
            if (o == null) return def;
            if (o instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private PlanNodeType parseNodeType(String text) {
        if (text == null || text.isBlank()) return PlanNodeType.TOOL;
        try {
            return PlanNodeType.valueOf(text.trim().toUpperCase(Locale.ROOT));
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

    private Map<String, Object> extractDataPayload(String toolJsonResult) {
        JsonNode n = safeNode(toolJsonResult);
        if (n == null) return Map.of();
        if (n.has("data")) {
            try {
                return objectMapper.convertValue(n.get("data"), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
            }
        }
        try {
            return objectMapper.convertValue(n, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
