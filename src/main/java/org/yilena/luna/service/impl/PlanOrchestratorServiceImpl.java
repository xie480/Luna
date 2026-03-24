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

                Map<String, Object> outputForNext = new