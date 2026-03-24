package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.PlanEdge;
import org.yilena.luna.entity.PlanInstance;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.entity.PlanPhase;
import org.yilena.luna.enums.PlanNodeStatus;
import org.yilena.luna.enums.PlanNodeType;
import org.yilena.luna.enums.PlanPhaseStatus;
import org.yilena.luna.enums.PlanRiskLevel;
import org.yilena.luna.enums.PlanStatus;
import org.yilena.luna.mapper.PlanEdgeMapper;
import org.yilena.luna.mapper.PlanInstanceMapper;
import org.yilena.luna.mapper.PlanNodeMapper;
import org.yilena.luna.mapper.PlanPhaseMapper;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.tools.PlanBlueprintTools;
import org.yilena.luna.tools.PlanEventTools;
import org.yilena.luna.tools.PlanNodeTools;
import org.yilena.luna.tools.PlanReportTools;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.time.LocalDateTime;
import java.util.*;

/**
 * OpenClaw 计划编排服务实现（MVP）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanOrchestratorServiceImpl implements PlanOrchestratorService {

    private final ObjectMapper objectMapper;
    private final PlanInstanceMapper planInstanceMapper;
    private final PlanNodeMapper planNodeMapper;
    private final PlanPhaseMapper planPhaseMapper;
    private final PlanEdgeMapper planEdgeMapper;

    private final PlanBlueprintTools planBlueprintTools;
    private final PlanNodeTools planNodeTools;
    private final PlanEventTools planEventTools;
    private final PlanReportTools planReportTools;

    @Override
    public String createAndRunPlan(String sessionId, String userGoal) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "sessionId 不能为空");
            }
            if (userGoal == null || userGoal.isBlank()) {
                return error("PLAN_INVALID_INPUT", "userGoal 不能为空");
            }

            String planId = "plan-" + SnowflakeIdUtil.nextIdStr();
            int planVersion = 1;

            PlanInstance instance = PlanInstance.builder()
                    .planId(planId)
                    .sessionId(sessionId)
                    .userGoal(userGoal)
                    .planVersion(planVersion)
                    .status(PlanStatus.PENDING)
                    .currentLoopIndex(0)
                    .planningModel("mvp-local-planner")
                    .startedAt(LocalDateTime.now())
                    .build();
            planInstanceMapper.insert(instance);

            Map<String, Object> blueprint = buildMvpBlueprint(planId, sessionId, userGoal);
            String validateErr = validateBlueprint(blueprint);
            if (validateErr != null) {
                return error("PLAN_BLUEPRINT_INVALID", validateErr);
            }

            String saveResult = planBlueprintTools.savePlanBlueprint(
                    planId,
                    planVersion,
                    objectMapper.writeValueAsString(blueprint),
                    "mvp-local-planner",
                    LocalDateTime.now().toString()
            );
            if (isError(saveResult)) {
                markPlanFailed(planId, "保存蓝图失败");
                return saveResult;
            }

            List<String> phaseIdsFromBlueprint = extractPhaseIds(blueprint);
            if (phaseIdsFromBlueprint.isEmpty()) {
                phaseIdsFromBlueprint = List.of(defaultPhaseId(planId, 1));
            }

            for (int i = 0; i < phaseIdsFromBlueprint.size(); i++) {
                String phaseId = phaseIdsFromBlueprint.get(i);
                PlanPhase phase = PlanPhase.builder()
                        .phaseId(phaseId)
                        .planId(planId)
                        .phaseOrder(i + 1)
                        .name("MVP_PHASE_" + (i + 1))
                        .objective("执行阶段 " + (i + 1))
                        .status(PlanPhaseStatus.PENDING)
                        .build();
                planPhaseMapper.insert(phase);
            }

            for (String phaseId : phaseIdsFromBlueprint) {
                PlanNode node = PlanNode.builder()
                        .nodeId("node-" + SnowflakeIdUtil.nextIdStr())
                        .planId(planId)
                        .phaseId(phaseId)
                        .name("mvp-node-" + phaseId)
                        .nodeType(PlanNodeType.TOOL)
                        .riskLevel(PlanRiskLevel.LOW)
                        .status(PlanNodeStatus.PENDING)
                        .retryCount(0)
                        .maxRetry(0)
                        .build();
                planNodeMapper.insert(node);

                Map<String, Object> createdPayload = new LinkedHashMap<>();
                createdPayload.put("eventType", "PLAN_CREATED");
                createdPayload.put("planId", planId);
                createdPayload.put("phaseId", phaseId);
                createdPayload.put("nodeId", node.getNodeId());
                createdPayload.put("status", "PENDING");
                createdPayload.put("message", "阶段节点已创建");
                createdPayload.put("skillName", node.getName());
                createdPayload.put("nodeType", node.getNodeType() != null ? node.getNodeType().getValue() : "");
                createdPayload.put("failReason", "");
                createdPayload.put("errorCode", "");
                createdPayload.put("retryCount", node.getRetryCount() == null ? 0 : node.getRetryCount());
                createdPayload.put("costMs", 0);
                createdPayload.put("outputForNext", Map.of());
                createdPayload.put("phaseOrder", phaseOrderOf(planId, phaseId));
                createdPayload.put("successCount", 0);
                createdPayload.put("failCount", 0);
                createdPayload.put("timestamp", System.currentTimeMillis());

                emitPlanEvent("PLAN_CREATED", "INFO", planId, phaseId, node.getNodeId(), createdPayload);
            }

            buildSimplePlanEdges(planId);
            updatePlanStatus(planId, PlanStatus.RUNNING, null);

            List<PlanPhase> orderedPhases = loadOrderedPhases(planId);
            if (orderedPhases.isEmpty()) {
                markPlanFailed(planId, "未找到可执行阶段");
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
                merged.put("status", "error");
                merged.put("message", "计划阶段执行失败，已生成报告");
                return objectMapper.writeValueAsString(merged);
            }

            if (isError(reportResult)) {
                updatePlanStatus(planId, PlanStatus.FAILED, "报告生成失败");
                merged.put("status", "error");
                merged.put("message", "计划执行完成，但报告生成失败");
                return objectMapper.writeValueAsString(merged);
            }

            updatePlanStatus(planId, PlanStatus.SUCCESS, null);
            merged.put("status", "success");
            merged.put("message", "计划多阶段执行成功并生成报告");
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.error("createAndRunPlan 失败", e);
            return error("PLAN_CREATE_RUN_FAILED", "创建并执行计划失败: " + e.getMessage());
        }
    }

    @Override
    public String runPhase(String planId, String phaseId) {
        try {
            if (planId == null || planId.isBlank() || phaseId == null || phaseId.isBlank()) {
                return error("PHASE_INVALID_INPUT", "planId 和 phaseId 不能为空");
            }

            String listResult = planNodeTools.listPhaseNodes(planId, phaseId);
            if (isError(listResult)) {
                return listResult;
            }

            List<PlanNode> nodes = planNodeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanNode>()
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

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("nodeName", node.getName());
                output.put("result", "ok");
                output.put("phaseId", phaseId);

                Map<String, Object> outputForNext = new LinkedHashMap<>();
                outputForNext.put("result", "ok");

                String append = planNodeTools.appendNodeOutput(
                        planId,
                        nodeId,
                        objectMapper.writeValueAsString(output),
                        objectMapper.writeValueAsString(outputForNext)
                );

                if (isError(append)) {
                    long cost = System.currentTimeMillis() - nodeStart;
                    planNodeTools.updateNodeStatus(
                            planId,
                            nodeId,
                            "FAILED",
                            cost,
                            "append_node_output failed",
                            retryCount
                    );

                    failCount++;
                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "ERROR",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "节点输出写入失败", skillName, nodeType,
                                    "append_node_output failed", "NODE_OUTPUT_APPEND_FAILED",
                                    retryCount, cost, Map.of(), System.currentTimeMillis()
                            )
                    );
                    continue;
                }

                long cost = System.currentTimeMillis() - nodeStart;
                String success = planNodeTools.updateNodeStatus(
                        planId,
                        nodeId,
                        "SUCCESS",
                        cost,
                        null,
                        retryCount
                );

                if (isError(success)) {
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
                                    retryCount, cost, buildOutputSummary(outputForNext), System.currentTimeMillis()
                            )
                    );
                    continue;
                }

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
                                "", "", retryCount, cost, buildOutputSummary(outputForNext), System.currentTimeMillis()
                        )
                );
            }

            String progress = planNodeTools.queryPlanProgress(planId);
            long phaseCost = System.currentTimeMillis() - phaseStart;

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
                            "status", failCount > 0 ? "FAILED" : "SUCCESS",
                            "message", failCount > 0 ? "阶段执行结束（含失败）" : "阶段执行完成",
                            "phaseOrder", phaseOrder,
                            "successCount", successCount,
                            "failCount", failCount,
                            "costMs", phaseCost,
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
            out.put("costMs", phaseCost);
            out.put("progress", safeParse(progress));

            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("runPhase 失败", e);
            return error("PHASE_RUN_FAILED", "阶段执行失败: " + e.getMessage());
        }
    }

    @Override
    public String finalizeAndReport(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能为空");
            }

            String loaded = planBlueprintTools.loadPlanBlueprint(planId, null);
            Map<String, Object> loadedObj = safeParse(loaded);

            List<PlanNode> nodes = planNodeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanNode>()
                            .eq(PlanNode::getPlanId, planId)
                            .orderByAsc(PlanNode::getPhaseId)
                            .orderByAsc(PlanNode::getNodeId)
            );

            long success = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.SUCCESS).count();
            long failed = nodes.stream().filter(n -> n.getStatus() == PlanNodeStatus.FAILED).count();
            String finalStatus = failed > 0 ? "FAILED" : "SUCCESS";

            String html = buildReportHtml(planId, finalStatus, success, failed, loadedObj, nodes);

            String writeResult = planReportTools.writeHtmlReportFile(
                    planId,
                    html,
                    planId + ".html",
                    "./data/reports"
            );
            if (isError(writeResult)) {
                return writeResult;
            }

            Map<String, Object> writeObj = safeParse(writeResult);
            Object dataObj = writeObj.get("data");
            String reportPath = "";
            if (dataObj instanceof Map<?, ?> dataMap) {
                Object reportPathObj = dataMap.get("reportPath");
                reportPath = reportPathObj == null ? "" : String.valueOf(reportPathObj);
            }

            String openResult = "";
            if (!reportPath.isBlank()) {
                openResult = planReportTools.openBrowserWithFile(reportPath);
            }

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
                            "message", "报告已生成",
                            "reportPath", reportPath,
                            "timestamp", System.currentTimeMillis()
                    )
            );

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("planId", planId);
            out.put("finalStatus", finalStatus);
            out.put("writeResult", safeParse(writeResult));
            out.put("openResult", safeParse(openResult));

            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("finalizeAndReport 失败", e);
            return error("PLAN_REPORT_FAILED", "计划报告生成失败: " + e.getMessage());
        }
    }

    @Override
    public String getPlanGraph(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能为空");
            }

            List<PlanPhase> phases = planPhaseMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanPhase>()
                            .eq(PlanPhase::getPlanId, planId)
                            .orderByAsc(PlanPhase::getPhaseOrder)
                            .orderByAsc(PlanPhase::getPhaseId)
            );

            List<Map<String, Object>> phaseList = new ArrayList<>();
            for (PlanPhase phase : phases) {
                List<PlanNode> nodes = planNodeMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanNode>()
                                .eq(PlanNode::getPlanId, planId)
                                .eq(PlanNode::getPhaseId, phase.getPhaseId())
                                .orderByAsc(PlanNode::getNodeId)
                );

                List<Map<String, Object>> nodeList = new ArrayList<>();
                for (PlanNode n : nodes) {
                    nodeList.add(Map.of(
                            "nodeId", n.getNodeId() == null ? "" : n.getNodeId(),
                            "nodeName", n.getName() == null ? "" : n.getName(),
                            "skillName", n.getName() == null ? "" : n.getName(),
                            "nodeType", n.getNodeType() == null ? "" : n.getNodeType().getValue(),
                            "status", n.getStatus() == null ? "PENDING" : n.getStatus().getValue(),
                            "failReason", n.getFailReason() == null ? "" : n.getFailReason(),
                            "outputForNext", buildOutputSummary(n.getOutputForNext()),
                            "costMs", n.getCostMs() == null ? 0L : n.getCostMs()
                    ));
                }

                phaseList.add(Map.of(
                        "phaseId", phase.getPhaseId() == null ? "" : phase.getPhaseId(),
                        "phaseOrder", phase.getPhaseOrder() == null ? 0 : phase.getPhaseOrder(),
                        "name", phase.getName() == null ? "" : phase.getName(),
                        "status", phase.getStatus() == null ? "PENDING" : phase.getStatus().getValue(),
                        "nodes", nodeList
                ));
            }

            List<PlanEdge> edges = planEdgeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanEdge>()
                            .eq(PlanEdge::getPlanId, planId)
                            .orderByAsc(PlanEdge::getId)
            );

            List<Map<String, Object>> edgeList = new ArrayList<>();
            for (PlanEdge e : edges) {
                edgeList.add(Map.of(
                        "fromNodeId", e.getFromNodeId() == null ? "" : e.getFromNodeId(),
                        "toNodeId", e.getToNodeId() == null ? "" : e.getToNodeId(),
                        "conditionExpr", e.getConditionExpr() == null ? "" : e.getConditionExpr()
                ));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planId", planId);
            data.put("phases", phaseList);
            data.put("edges", edgeList);

            return objectMapper.writeValueAsString(Map.of(
                    "status", "success",
                    "data", data
            ));
        } catch (Exception e) {
            log.error("getPlanGraph 失败", e);
            return error("PLAN_GRAPH_FAILED", "获取计划图谱失败: " + e.getMessage());
        }
    }

    private void buildSimplePlanEdges(String planId) {
        try {
            planEdgeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanEdge>()
                    .eq(PlanEdge::getPlanId, planId));

            List<PlanPhase> phases = loadOrderedPhases(planId);
            if (phases.isEmpty()) {
                return;
            }

            String prevPhaseTailNodeId = null;

            for (PlanPhase phase : phases) {
                List<PlanNode> nodes = planNodeMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanNode>()
                                .eq(PlanNode::getPlanId, planId)
                                .eq(PlanNode::getPhaseId, phase.getPhaseId())
                                .orderByAsc(PlanNode::getNodeId)
                );

                String currentPhaseHeadNodeId = null;
                String currentPhaseTailNodeId = null;

                for (int i = 0; i < nodes.size(); i++) {
                    PlanNode current = nodes.get(i);
                    if (i == 0) currentPhaseHeadNodeId = current.getNodeId();
                    currentPhaseTailNodeId = current.getNodeId();

                    if (i > 0) {
                        PlanNode prev = nodes.get(i - 1);
                        insertEdge(planId, prev.getNodeId(), current.getNodeId(), "");
                    }
                }

                if (prevPhaseTailNodeId != null && currentPhaseHeadNodeId != null) {
                    insertEdge(planId, prevPhaseTailNodeId, currentPhaseHeadNodeId, "PHASE_FLOW");
                }

                if (currentPhaseTailNodeId != null) {
                    prevPhaseTailNodeId = currentPhaseTailNodeId;
                }
            }
        } catch (Exception e) {
            log.warn("构建 plan_edge 失败, planId={}, err={}", planId, e.getMessage());
        }
    }

    private void insertEdge(String planId, String fromNodeId, String toNodeId, String conditionExpr) {
        if (fromNodeId == null || toNodeId == null) return;
        if (fromNodeId.equals(toNodeId)) return;

        PlanEdge edge = PlanEdge.builder()
                .planId(planId)
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .conditionExpr(conditionExpr)
                .build();
        planEdgeMapper.insert(edge);
    }

    private int phaseOrderOf(String planId, String phaseId) {
        PlanPhase phase = planPhaseMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanPhase>()
                        .eq(PlanPhase::getPlanId, planId)
                        .eq(PlanPhase::getPhaseId, phaseId)
                        .last("LIMIT 1")
        );
        return phase == null || phase.getPhaseOrder() == null ? 0 : phase.getPhaseOrder();
    }

    private Map<String, Object> buildOutputSummary(Map<String, Object> outputForNext) {
        if (outputForNext == null || outputForNext.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(outputForNext.keySet());
        summary.put("keys", keys.size() > 20 ? keys.subList(0, 20) : keys);

        String raw;
        try {
            raw = objectMapper.writeValueAsString(outputForNext);
        } catch (Exception e) {
            raw = String.valueOf(outputForNext);
        }

        if (raw.length() > 500) {
            raw = raw.substring(0, 500) + "...";
        }
        summary.put("preview", raw);
        return summary;
    }

    private Map<String, Object> buildNodeEventPayload(String eventType,
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
                                                      long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("planId", planId);
        payload.put("phaseId", phaseId);
        payload.put("nodeId", nodeId);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("skillName", skillName == null ? "" : skillName);
        payload.put("nodeType", nodeType == null ? "" : nodeType);
        payload.put("failReason", failReason == null ? "" : failReason);
        payload.put("errorCode", errorCode == null ? "" : errorCode);
        payload.put("retryCount", retryCount);
        payload.put("costMs", costMs);
        payload.put("outputForNext", outputForNext == null ? Map.of() : outputForNext);
        payload.put("timestamp", timestamp);
        return payload;
    }

    private void emitPlanEvent(String eventType,
                               String level,
                               String planId,
                               String phaseId,
                               String nodeId,
                               Map<String, Object> payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String result = planEventTools.emitPlanEvent(
                    "default",
                    planId,
                    (phaseId == null || phaseId.isBlank()) ? null : phaseId,
                    (nodeId == null || nodeId.isBlank()) ? null : nodeId,
                    level,
                    eventType,
                    payloadJson,
                    "plan-" + planId
            );
            if (isError(result)) {
                log.warn("emitPlanEvent 双写返回错误, eventType={}, result={}", eventType, result);
            }
        } catch (Exception e) {
            log.warn("emitPlanEvent 失败, eventType={}, err={}", eventType, e.getMessage());
        }
    }

    private void updatePlanStatus(String planId, PlanStatus status, String errorMessage) {
        try {
            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) return;
            instance.setStatus(status);
            if (errorMessage != null && !errorMessage.isBlank()) {
                instance.setErrorMessage(errorMessage);
            }
            if (status == PlanStatus.SUCCESS || status == PlanStatus.FAILED || status == PlanStatus.CANCELLED) {
                instance.setFinishedAt(LocalDateTime.now());
            }
            planInstanceMapper.updateById(instance);
        } catch (Exception e) {
            log.warn("更新 plan_instance 状态失败, planId={}, status={}, err={}", planId, status.getValue(), e.getMessage());
        }
    }

    private void markPlanFailed(String planId, String errorMessage) {
        updatePlanStatus(planId, PlanStatus.FAILED, errorMessage);
    }

    private List<PlanPhase> loadOrderedPhases(String planId) {
        return planPhaseMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanPhase>()
                        .eq(PlanPhase::getPlanId, planId)
                        .orderByAsc(PlanPhase::getPhaseOrder)
                        .orderByAsc(PlanPhase::getPhaseId)
        );
    }

    private void markPhaseStatus(PlanPhase phase, PlanPhaseStatus status, boolean markStartedAt, boolean markFinishedAt) {
        try {
            phase.setStatus(status);
            if (markStartedAt && phase.getStartedAt() == null) {
                phase.setStartedAt(LocalDateTime.now());
            }
            if (markFinishedAt) {
                phase.setFinishedAt(LocalDateTime.now());
            }
            planPhaseMapper.updateById(phase);
        } catch (Exception e) {
            log.warn("阶段状态更新失败, planId={}, phaseId={}, status={}, err={}",
                    phase.getPlanId(), phase.getPhaseId(), status.getValue(), e.getMessage());
        }
    }

    private Map<String, Object> buildMvpBlueprint(String planId, String sessionId, String userGoal) {
        Map<String, Object> blueprint = new LinkedHashMap<>();
        blueprint.put("planId", planId);
        blueprint.put("sessionId", sessionId);
        blueprint.put("userGoal", userGoal);
        blueprint.put("createdAt", LocalDateTime.now().toString());

        boolean multiPhase = shouldUseMultiPhase(userGoal);

        List<Map<String, Object>> phases = new ArrayList<>();
        Map<String, Object> phase1 = new LinkedHashMap<>();
        phase1.put("phaseId", defaultPhaseId(planId, 1));
        phase1.put("name", "MVP_PHASE_1");
        phase1.put("objective", "执行阶段一");
        phases.add(phase1);

        if (multiPhase) {
            Map<String, Object> phase2 = new LinkedHashMap<>();
            phase2.put("phaseId", defaultPhaseId(planId, 2));
            phase2.put("name", "MVP_PHASE_2");
            phase2.put("objective", "执行阶段二");
            phases.add(phase2);
        }

        blueprint.put("phases", phases);
        return blueprint;
    }

    private String defaultPhaseId(String planId, int order) {
        return planId + ":phase-" + order;
    }

    private boolean shouldUseMultiPhase(String userGoal) {
        if (userGoal == null) return false;
        String t = userGoal.toLowerCase(Locale.ROOT);
        return t.contains("多阶段")
                || t.contains("分阶段")
                || t.contains("两阶段")
                || t.contains("三阶段")
                || t.contains("multi phase")
                || t.contains("multi-phase")
                || t.contains("phase");
    }

    private List<String> extractPhaseIds(Map<String, Object> blueprint) {
        Object phasesObj = blueprint.get("phases");
        if (!(phasesObj instanceof List<?> phases)) {
            return new ArrayList<>();
        }

        List<String> ids = new ArrayList<>();
        for (Object p : phases) {
            if (p instanceof Map<?, ?> pm) {
                Object phaseId = pm.get("phaseId");
                if (phaseId instanceof String s && !s.isBlank()) {
                    ids.add(s.trim());
                }
            }
        }
        return ids;
    }

    private String validateBlueprint(Map<String, Object> blueprint) {
        if (blueprint == null) return "blueprint 不能为空";

        Object planId = blueprint.get("planId");
        Object sessionId = blueprint.get("sessionId");
        Object userGoal = blueprint.get("userGoal");
        Object phases = blueprint.get("phases");

        if (!(planId instanceof String) || ((String) planId).isBlank()) return "planId 不能为空";
        if (!(sessionId instanceof String) || ((String) sessionId).isBlank()) return "sessionId 不能为空";
        if (!(userGoal instanceof String) || ((String) userGoal).isBlank()) return "userGoal 不能为空";

        if (!(phases instanceof List<?> phaseList) || phaseList.isEmpty()) {
            return "phases 不能为空且至少包含一个阶段";
        }

        Set<String> phaseIdSet = new HashSet<>();
        for (Object p : phaseList) {
            if (!(p instanceof Map<?, ?> pm)) return "phases 元素必须为对象";
            Object phaseId = pm.get("phaseId");
            if (!(phaseId instanceof String) || ((String) phaseId).isBlank()) {
                return "phase.phaseId 不能为空";
            }
            String pid = ((String) phaseId).trim();
            if (!phaseIdSet.add(pid)) {
                return "phase.phaseId 不能重复: " + pid;
            }
        }

        return null;
    }

    private boolean canTransitToRunning(PlanNodeStatus status) {
        if (status == null) return true;
        return status == PlanNodeStatus.PENDING
                || status == PlanNodeStatus.BLOCKED
                || status == PlanNodeStatus.APPROVAL_PENDING;
    }

    private String buildReportHtml(String planId,
                                   String finalStatus,
                                   long success,
                                   long failed,
                                   Map<String, Object> loadedObj,
                                   List<PlanNode> nodes) throws Exception {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"UTF-8\"><title>Plan Report</title></head><body>");
        html.append("<h1>OpenClaw Plan Report</h1>");
        html.append("<p><b>planId:</b> ").append(escapeHtml(planId)).append("</p>");
        html.append("<p><b>finalStatus:</b> ").append(escapeHtml(finalStatus)).append("</p>");
        html.append("<p><b>successNodes:</b> ").append(success).append("</p>");
        html.append("<p><b>failedNodes:</b> ").append(failed).append("</p>");
        html.append("<h2>Blueprint</h2><pre>")
                .append(escapeHtml(objectMapper.writeValueAsString(loadedObj)))
                .append("</pre>");
        html.append("<h2>Nodes</h2><ul>");
        for (PlanNode n : nodes) {
            html.append("<li>")
                    .append(escapeHtml(n.getNodeId()))
                    .append(" | ")
                    .append(escapeHtml(n.getName()))
                    .append(" | ")
                    .append(n.getStatus() == null ? "PENDING" : n.getStatus().getValue())
                    .append("</li>");
        }
        html.append("</ul></body></html>");
        return html.toString();
    }

    private boolean isError(String json) {
        Map<String, Object> m = safeParse(json);
        Object s = m.get("status");
        return s != null && "error".equalsIgnoreCase(String.valueOf(s));
    }

    private Map<String, Object> safeParse(String json) {
        try {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String error(String code, String msg) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", code,
                    "message", msg
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"errorCode\":\"" + code + "\",\"message\":\"" + msg + "\"}";
        }
    }

    private String escapeHtml(String src) {
        if (src == null) return "";
        return src.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
