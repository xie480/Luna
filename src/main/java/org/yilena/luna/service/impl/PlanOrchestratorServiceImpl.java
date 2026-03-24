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

/**
 * OpenClaw 计划编排服务实现（Master Planner 版）
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

                String nodeGoal = buildNodeGoal(planId, phaseId, node);
                String agentResult = agentService.processToolCalling(sessionId, nodeGoal);

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("nodeName", node.getName());
                output.put("phaseId", phaseId);
                output.put("nodeGoal", nodeGoal);
                output.put("agentResult", safeParse(agentResult));
                output.put("result", isError(agentResult) ? "error" : "ok");

                Map<String, Object> outputForNext = new LinkedHashMap<>();
                outputForNext.put("result", output.get("result"));
                outputForNext.put("agentResult", safeParse(agentResult));

                String append = planNodeTools.appendNodeOutput(
                        planId,
                        nodeId,
                        objectMapper.writeValueAsString(output),
                        objectMapper.writeValueAsString(outputForNext)
                );

                if (isError(append) || isError(agentResult)) {
                    long cost = System.currentTimeMillis() - nodeStart;
                    String failMsg = isError(agentResult) ? "agent execute failed" : "append_node_output failed";
                    planNodeTools.updateNodeStatus(
                            planId,
                            nodeId,
                            "FAILED",
                            cost,
                            failMsg,
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
                                    "节点执行失败", skillName, nodeType,
                                    failMsg, "NODE_EXECUTE_FAILED",
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

            Map<String, Object> phaseFinishedPayload = new LinkedHashMap<>();
            phaseFinishedPayload.put("eventType", "PLAN_PHASE_FINISHED");
            phaseFinishedPayload.put("planId", planId);
            phaseFinishedPayload.put("phaseId", phaseId);
            phaseFinishedPayload.put("nodeId", "");
            phaseFinishedPayload.put("status", failCount > 0 ? "FAILED" : "SUCCESS");
            phaseFinishedPayload.put("message", failCount > 0 ? "阶段执行结束（含失败）" : "阶段执行完成");
            phaseFinishedPayload.put("phaseOrder", phaseOrder);
            phaseFinishedPayload.put("successCount", successCount);
            phaseFinishedPayload.put("failCount", failCount);
            phaseFinishedPayload.put("costMs", phaseCost);
            phaseFinishedPayload.put("timestamp", System.currentTimeMillis());

            emitPlanEvent(
                    "PLAN_PHASE_FINISHED",
                    failCount > 0 ? "WARN" : "INFO",
                    planId,
                    phaseId,
                    "",
                    phaseFinishedPayload
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

    private String resolveSessionIdByPlanId(String planId) {
        try {
            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance != null && instance.getSessionId() != null && !instance.getSessionId().isBlank()) {
                return instance.getSessionId();
            }
        } catch (Exception e) {
            log.warn("读取 plan sessionId 失败, planId={}, err={}", planId, e.getMessage());
        }
        return planId;
    }

    private void materializePhasesAndNodes(String planId, Map<String, Object> blueprint) {
        List<Map<String, Object>> phases = readListOfMap(blueprint.get("phases"));
        List<Map<String, Object>> nodes = readListOfMap(blueprint.get("nodes"));

        Map<String, Integer> phaseOrderMap = new HashMap<>();

        for (Map<String, Object> p : phases) {
            String phaseId = str(p.get("phaseId"));
            String name = str(p.get("name"));
            String objective = str(p.get("objective"));
            Integer order = intVal(p.get("phaseOrder"), 0);

            PlanPhase phase = PlanPhase.builder()
                    .phaseId(phaseId)
                    .planId(planId)
                    .phaseOrder(order)
                    .name(name.isBlank() ? ("PHASE_" + order) : name)
                    .objective(objective.isBlank() ? "执行阶段" : objective)
                    .status(PlanPhaseStatus.PENDING)
                    .build();
            planPhaseMapper.insert(phase);

            phaseOrderMap.put(phaseId, order);
        }

        for (Map<String, Object> n : nodes) {
            String nodeId = str(n.get("nodeId"));
            String phaseId = str(n.get("phaseId"));
            String name = str(n.get("name"));
            String nodeTypeStr = str(n.get("nodeType"));
            String riskLevelStr = str(n.get("riskLevel"));

            PlanNodeType nodeType = parseNodeType(nodeTypeStr);
            PlanRiskLevel riskLevel = parseRiskLevel(riskLevelStr);

            PlanNode node = PlanNode.builder()
                    .nodeId(nodeId.isBlank() ? ("node-" + SnowflakeIdUtil.nextIdStr()) : nodeId)
                    .planId(planId)
                    .phaseId(phaseId)
                    .name(name.isBlank() ? "node-" + phaseId : name)
                    .nodeType(nodeType)
                    .riskLevel(riskLevel)
                    .status(PlanNodeStatus.PENDING)
                    .retryCount(0)
                    .maxRetry(1)
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
            createdPayload.put("phaseOrder", phaseOrderMap.getOrDefault(phaseId, 0));
            createdPayload.put("successCount", 0);
            createdPayload.put("failCount", 0);
            createdPayload.put("timestamp", System.currentTimeMillis());

            emitPlanEvent("PLAN_CREATED", "INFO", planId, phaseId, node.getNodeId(), createdPayload);
        }
    }

    private void buildEdgesFromBlueprint(String planId, Map<String, Object> blueprint) {
        try {
            planEdgeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanEdge>()
                    .eq(PlanEdge::getPlanId, planId));

            List<Map<String, Object>> edges = readListOfMap(blueprint.get("edges"));
            if (edges.isEmpty()) {
                buildSimplePlanEdges(planId);
                return;
            }

            for (Map<String, Object> e : edges) {
                String from = str(e.get("fromNodeId"));
                String to = str(e.get("toNodeId"));
                String cond = str(e.get("conditionExpr"));
                insertEdge(planId, from, to, cond);
            }
        } catch (Exception e) {
            log.warn("构建 plan_edge 失败, planId={}, err={}", planId, e.getMessage());
        }