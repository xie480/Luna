package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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

            PlanInstance planInstance = planInstanceMapper.selectById(planId);
            String sessionId = resolveSessionIdByPlanId(planId);
            int planVersion = planInstance == null || planInstance.getPlanVersion() == null ? 0 : planInstance.getPlanVersion();

            String listResult = planNodeTools.listPhaseNodes(planId, phaseId);
            if (isError(listResult)) {
                log.error("runPhase 列出节点失败, planId={}, phaseId={}", planId, phaseId);
                return listResult;
            }

            List<PlanNode> nodes = planNodeMapper.selectList(
                    new LambdaQueryWrapper<PlanNode>()
                            .eq(PlanNode::getPlanId, planId)
                            .eq(PlanNode::getPhaseId, phaseId)
                            .orderByAsc(PlanNode::getNodeId)
            );

            if (nodes.isEmpty()) {
                log.warn("runPhase 阶段无节点, planId={}, phaseId={}", planId, phaseId);
                return error("PHASE_EMPTY", "阶段下无可执行节点");
            }

            long phaseStart = System.currentTimeMillis();
            int successCount = 0;
            int failCount = 0;
            int phaseOrder = phaseOrderOf(planId, phaseId);

            log.info("runPhase 开始执行, planId={}, phaseId={}, phaseOrder={}, nodeTotal={}", planId, phaseId, phaseOrder, nodes.size());

            Map<String, Object> phaseStartPayload = new LinkedHashMap<>();
            phaseStartPayload.put("eventType", "PLAN_PHASE_STARTED");
            phaseStartPayload.put("planId", planId);
            phaseStartPayload.put("phaseId", phaseId);
            phaseStartPayload.put("nodeId", "");
            phaseStartPayload.put("status", "RUNNING");
            phaseStartPayload.put("message", "阶段开始执行");
            phaseStartPayload.put("phaseOrder", phaseOrder);
            phaseStartPayload.put("planVersion", planVersion);
            phaseStartPayload.put("sessionId", sessionId);
            phaseStartPayload.put("successCount", 0);
            phaseStartPayload.put("failCount", 0);
            phaseStartPayload.put("timestamp", System.currentTimeMillis());

            emitPlanEvent(
                    "PLAN_PHASE_STARTED",
                    "INFO",
                    planId,
                    phaseId,
                    "",
                    phaseStartPayload
            );

            for (PlanNode node : nodes) {
                long nodeStart = System.currentTimeMillis();
                String nodeId = node.getNodeId();
                String skillName = node.getName() == null ? "" : node.getName();
                String declaredNodeType = node.getNodeType() == null ? "" : node.getNodeType().getValue();
                int retryCount = node.getRetryCount() == null ? 0 : node.getRetryCount();
                int maxRetry = node.getMaxRetry() == null ? DEFAULT_MAX_RETRY : node.getMaxRetry();
                String resourceHintText = toJsonQuiet(node.getResourceHint());
                String inputJsonText = toJsonQuiet(node.getInputJson());

                logNodeStart(planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType, retryCount, maxRetry);

                if (!canTransitToRunning(node.getStatus())) {
                    failCount++;
                    logNodeFailure(
                            planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                            retryCount, maxRetry, 0L,
                            "节点状态流转不合法", "NODE_INVALID_TRANSITION"
                    );
                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "WARN",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "节点状态流转不合法", skillName, declaredNodeType,
                                    "非法状态流转", "NODE_INVALID_TRANSITION",
                                    retryCount, maxRetry, 0L, Map.of(), System.currentTimeMillis(),
                                    resourceHintText, inputJsonText,
                                    "", "", "",
                                    planVersion, sessionId
                            )
                    );
                    continue;
                }

                String running = planNodeTools.updateNodeStatus(
                        planId, nodeId, "RUNNING", null, null, retryCount
                );
                if (isError(running)) {
                    failCount++;
                    long costMs = System.currentTimeMillis() - nodeStart;
                    logNodeFailure(
                            planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                            retryCount, maxRetry, costMs,
                            "更新节点运行状态失败", "NODE_RUNNING_UPDATE_FAILED"
                    );
                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "ERROR",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "更新节点运行状态失败", skillName, declaredNodeType,
                                    "update_node_status RUNNING failed", "NODE_RUNNING_UPDATE_FAILED",
                                    retryCount, maxRetry, costMs, Map.of(), System.currentTimeMillis(),
                                    resourceHintText, inputJsonText,
                                    "", "", "",
                                    planVersion, sessionId
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
                                "节点执行中", skillName, declaredNodeType,
                                "", "", retryCount, maxRetry, 0L, Map.of(), System.currentTimeMillis(),
                                resourceHintText, inputJsonText,
                                "", "", "",
                                planVersion, sessionId
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
                        logNodeFailure(
                                planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                                retryCount, maxRetry, nodeCostMs,
                                "节点输出落库失败", "NODE_OUTPUT_APPEND_FAILED"
                        );
                        emitPlanEvent(
                                "PLAN_NODE_FAILED",
                                "ERROR",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                        "节点输出落库失败", skillName, declaredNodeType,
                                        "append_node_output failed", "NODE_OUTPUT_APPEND_FAILED",
                                        retryCount, maxRetry, nodeCostMs, outputForNext, System.currentTimeMillis(),
                                        resourceHintText, inputJsonText,
                                        "", "", "",
                                        planVersion, sessionId
                                )
                        );
                        continue;
                    }

                    String successRet = planNodeTools.updateNodeStatus(
                            planId, nodeId, "SUCCESS", nodeCostMs, null, retryCount
                    );
                    if (isError(successRet)) {
                        failCount++;
                        logNodeFailure(
                                planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                                retryCount, maxRetry, nodeCostMs,
                                "更新节点成功状态失败", "NODE_SUCCESS_UPDATE_FAILED"
                        );
                        emitPlanEvent(
                                "PLAN_NODE_FAILED",
                                "ERROR",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                        "更新节点成功状态失败", skillName, declaredNodeType,
                                        "update_node_status SUCCESS failed", "NODE_SUCCESS_UPDATE_FAILED",
                                        retryCount, maxRetry, nodeCostMs, outputForNext, System.currentTimeMillis(),
                                        resourceHintText, inputJsonText,
                                        "", "", "",
                                        planVersion, sessionId
                                )
                        );
                    } else {
                        successCount++;
                        log.info(
                                "plan node 执行成功, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, retryCount={}/{}, costMs={}",
                                planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType, retryCount, maxRetry, nodeCostMs
                        );
                        emitPlanEvent(
                                "PLAN_NODE_SUCCESS",
                                "INFO",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_SUCCESS", planId, phaseId, nodeId, "SUCCESS",
                                        "节点执行成功", skillName, declaredNodeType,
                                        "", "", retryCount, maxRetry, nodeCostMs, outputForNext, System.currentTimeMillis(),
                                        resourceHintText, inputJsonText,
                                        "", "", "",
                                        planVersion, sessionId
                                )
                        );
                    }
                    continue;
                }

                String failReason = extractErrorMessage(agentResult);
                String failCode = extractErrorCode(agentResult);
                String failedSkillName = extractSkillName(agentResult);
                String missingToolSlot = extractMissingToolSlot(agentResult);
                String missingCapability = extractMissingCapability(agentResult);

                boolean recovered = false;
                for (int r = retryCount + 1; r <= maxRetry; r++) {
                    log.warn(
                            "plan node 准备重试, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, nextRetry={}/{}, lastFailReason={}, lastErrorCode={}",
                            planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType, r, maxRetry, failReason, failCode
                    );
                    String retryRun = planNodeTools.updateNodeStatus(planId, nodeId, "RUNNING", null, null, r);
                    if (isError(retryRun)) {
                        logNodeFailure(
                                planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                                r, maxRetry, System.currentTimeMillis() - nodeStart,
                                "重试前更新 RUNNING 状态失败", "NODE_RETRY_RUNNING_UPDATE_FAILED"
                        );
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
                        log.info(
                                "plan node 重试成功, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, retryCount={}/{}, costMs={}",
                                planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType, r, maxRetry, System.currentTimeMillis() - nodeStart
                        );
                        emitPlanEvent(
                                "PLAN_NODE_SUCCESS",
                                "INFO",
                                planId,
                                phaseId,
                                nodeId,
                                buildNodeEventPayload(
                                        "PLAN_NODE_SUCCESS", planId, phaseId, nodeId, "SUCCESS",
                                        "节点重试后成功", skillName, declaredNodeType,
                                        "", "", r, maxRetry, System.currentTimeMillis() - nodeStart, retryOutputForNext, System.currentTimeMillis(),
                                        resourceHintText, inputJsonText,
                                        "", "", "",
                                        planVersion, sessionId
                                )
                        );
                        break;
                    } else {
                        failReason = extractErrorMessage(retryResult);
                        failCode = extractErrorCode(retryResult);
                        failedSkillName = extractSkillName(retryResult);
                        missingToolSlot = extractMissingToolSlot(retryResult);
                        missingCapability = extractMissingCapability(retryResult);
                    }
                }

                if (!recovered) {
                    failCount++;
                    long finalCostMs = System.currentTimeMillis() - nodeStart;

                    if ("SKILL_TOOL_MISSING".equalsIgnoreCase(failCode)) {
                        String skillDisplay = (failedSkillName == null || failedSkillName.isBlank()) ? skillName : failedSkillName;
                        failReason = "skill=" + skillDisplay
                                + " 缺少 capability=" + (missingCapability == null ? "" : missingCapability)
                                + " (slot=" + (missingToolSlot == null ? "" : missingToolSlot) + ")";
                    }

                    outputForNext.put("errorCode", failCode == null ? "" : failCode);
                    outputForNext.put("failReason", failReason == null ? "" : failReason);
                    outputForNext.put("failedSkillName", failedSkillName == null ? "" : failedSkillName);
                    outputForNext.put("missingToolSlot", missingToolSlot == null ? "" : missingToolSlot);
                    outputForNext.put("missingCapability", missingCapability == null ? "" : missingCapability);

                    planNodeTools.updateNodeStatus(
                            planId, nodeId, "FAILED", finalCostMs, failReason, maxRetry
                    );
                    logNodeFailure(
                            planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                            maxRetry, maxRetry, finalCostMs,
                            failReason, failCode
                    );
                    logPotentialToolSkillMismatch(planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType, failCode, failReason);
                    logInvalidSkillConfig(planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType, failCode, failReason, resourceHintText, inputJsonText);

                    if ("SKILL_TOOL_MISSING".equalsIgnoreCase(failCode)) {
                        log.error(
                                "plan skill 缺少工具, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, failedSkillName={}, missingToolSlot={}, missingCapability={}, failReason={}",
                                planId, phaseId, phaseOrder, nodeId, skillName, declaredNodeType,
                                failedSkillName == null ? "" : failedSkillName,
                                missingToolSlot == null ? "" : missingToolSlot,
                                missingCapability == null ? "" : missingCapability,
                                failReason
                        );
                    }

                    emitPlanEvent(
                            "PLAN_NODE_FAILED",
                            "WARN",
                            planId,
                            phaseId,
                            nodeId,
                            buildNodeEventPayload(
                                    "PLAN_NODE_FAILED", planId, phaseId, nodeId, "FAILED",
                                    "节点执行失败", skillName, declaredNodeType,
                                    failReason, failCode,
                                    maxRetry, maxRetry, finalCostMs, outputForNext, System.currentTimeMillis(),
                                    resourceHintText, inputJsonText,
                                    failedSkillName, missingToolSlot, missingCapability,
                                    planVersion, sessionId
                            )
                    );
                }
            }

            long phaseCostMs = System.currentTimeMillis() - phaseStart;
            String phaseStatus = failCount > 0 ? "FAILED" : "SUCCESS";

            Map<String, Object> phaseFinishPayload = new LinkedHashMap<>();
            phaseFinishPayload.put("eventType", "PLAN_PHASE_FINISHED");
            phaseFinishPayload.put("planId", planId);
            phaseFinishPayload.put("phaseId", phaseId);
            phaseFinishPayload.put("nodeId", "");
            phaseFinishPayload.put("status", phaseStatus);
            phaseFinishPayload.put("message", failCount > 0 ? "阶段执行完成（含失败节点）" : "阶段执行成功");
            phaseFinishPayload.put("phaseOrder", phaseOrder);
            phaseFinishPayload.put("planVersion", planVersion);
            phaseFinishPayload.put("sessionId", sessionId);
            phaseFinishPayload.put("successCount", successCount);
            phaseFinishPayload.put("failCount", failCount);
            phaseFinishPayload.put("costMs", phaseCostMs);
            phaseFinishPayload.put("timestamp", System.currentTimeMillis());

            emitPlanEvent(
                    "PLAN_PHASE_FINISHED",
                    failCount > 0 ? "WARN" : "INFO",
                    planId,
                    phaseId,
                    "",
                    phaseFinishPayload
            );

            log.info(
                    "runPhase 执行完成, planId={}, phaseId={}, phaseOrder={}, status={}, successCount={}, failCount={}, costMs={}",
                    planId, phaseId, phaseOrder, phaseStatus, successCount, failCount, phaseCostMs
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

            Map<String, Object> reportReadyPayload = new LinkedHashMap<>();
            reportReadyPayload.put("eventType", "PLAN_REPORT_READY");
            reportReadyPayload.put("planId", planId);
            reportReadyPayload.put("phaseId", "");
            reportReadyPayload.put("nodeId", "");
            reportReadyPayload.put("status", "SUCCESS");
            reportReadyPayload.put("message", "任务报告已生成");
            reportReadyPayload.put("planVersion", instance.getPlanVersion() == null ? 0 : instance.getPlanVersion());
            reportReadyPayload.put("sessionId", instance.getSessionId() == null ? "" : instance.getSessionId());
            reportReadyPayload.put("reportPath", reportPath);
            reportReadyPayload.put("reportUrl", reportUrl);
            reportReadyPayload.put("openResult", openFlag);
            reportReadyPayload.put("timestamp", System.currentTimeMillis());

            emitPlanEvent(
                    "PLAN_REPORT_READY",
                    "INFO",
                    planId,
                    "",
                    "",
                    reportReadyPayload
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

        Map<String, String> phaseIdMap = new LinkedHashMap<>();
        Map<String, List<String>> phaseNodeIds = new LinkedHashMap<>();

        int phaseIdx = 1;
        for (Map<String, Object> p : phaseDefs) {
            String rawPhaseId = text(p.get("phaseId"));
            if (rawPhaseId.isBlank()) {
                rawPhaseId = "phase-" + phaseIdx;
            }
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
                    .phaseId(fallbackPhaseId)
                    .planId(planId)
                    .phaseOrder(1)
                    .name("PHASE-1")
                    .objective("默认阶段")
                    .status(PlanPhaseStatus.PENDING)
                    .build();
            planPhaseMapper.insert(fallback);
            phaseNodeIds.put(fallbackPhaseId, new ArrayList<>());
            phaseIdMap.put("phase-1", fallbackPhaseId);
            phaseIdMap.put(fallbackPhaseId, fallbackPhaseId);
        }

        String defaultPhaseId = phaseNodeIds.keySet().stream().findFirst().orElse(normalizeScopedId(planId, "phase-1"));

        Map<String, String> nodeIdMap = new LinkedHashMap<>();
        int nodeIdx = 1;
        for (Map<String, Object> n : nodeDefs) {
            String rawNodeId = text(n.get("nodeId"));
            if (rawNodeId.isBlank()) {
                rawNodeId = "node-" + nodeIdx;
            }
            String nodeId = normalizeScopedId(planId, rawNodeId);

            nodeIdMap.put(rawNodeId, nodeId);
            nodeIdMap.put(nodeId, nodeId);

            String rawPhaseId = text(n.get("phaseId"));
            String phaseId = phaseIdMap.get(rawPhaseId);
            if (phaseId == null || !phaseNodeIds.containsKey(phaseId)) {
                phaseId = defaultPhaseId;
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
                    .dependencies(remapStringListIds(asStringList(n.get("dependencies")), nodeIdMap, planId, "node"))
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
            Map<String, String> phaseIdMap = asStringMap(blueprint.get("__phaseIdMap"));
            Map<String, String> nodeIdMap = asStringMap(blueprint.get("__nodeIdMap"));

            for (Map<String, Object> e : edges) {
                String rawFrom = text(e.get("fromNodeId"));
                String rawTo = text(e.get("toNodeId"));

                String from = resolveMappedOrScopedId(nodeIdMap, planId, rawFrom, "node");
                String to = resolveMappedOrScopedId(nodeIdMap, planId, rawTo, "node");

                if (from.isBlank() || to.isBlank()) {
                    continue;
                }

                boolean fromExists = planNodeMapper.selectCount(
                        new LambdaQueryWrapper<PlanNode>()
                                .eq(PlanNode::getPlanId, planId)
                                .eq(PlanNode::getNodeId, from)
                ) > 0;
                boolean toExists = planNodeMapper.selectCount(
                        new LambdaQueryWrapper<PlanNode>()
                                .eq(PlanNode::getPlanId, planId)
                                .eq(PlanNode::getNodeId, to)
                ) > 0;

                if (!fromExists || !toExists) {
                    continue;
                }

                PlanEdge edge = PlanEdge.builder()
                        .planId(planId)
                        .fromNodeId(from)
                        .toNodeId(to)
                        .conditionExpr(text(e.get("conditionExpr")))
                        .build();

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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "PLAN_CREATED");
        payload.put("planId", planId);
        payload.put("phaseId", "");
        payload.put("nodeId", "");
        payload.put("status", "PENDING");
        payload.put("message", "计划已创建");
        payload.put("sessionId", sessionId);
        payload.put("userGoal", userGoal);
        payload.put("planVersion", planVersion);
        payload.put("timestamp", System.currentTimeMillis());

        emitPlanEvent(
                "PLAN_CREATED",
                "INFO",
                planId,
                "",
                "",
                payload
        );
    }

    private void emitPlanFinished(String planId, String finalStatus, String message) {
        PlanInstance p = planInstanceMapper.selectById(planId);
        int planVersion = p == null || p.getPlanVersion() == null ? 0 : p.getPlanVersion();
        String sessionId = p == null || p.getSessionId() == null ? "" : p.getSessionId();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "PLAN_FINISHED");
        payload.put("planId", planId);
        payload.put("phaseId", "");
        payload.put("nodeId", "");
        payload.put("status", finalStatus);
        payload.put("message", message == null ? "" : message);
        payload.put("planVersion", planVersion);
        payload.put("sessionId", sessionId);
        payload.put("timestamp", System.currentTimeMillis());

        emitPlanEvent(
                "PLAN_FINISHED",
                "INFO",
                planId,
                "",
                "",
                payload
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
            String declaredNodeType,
            String failReason,
            String errorCode,
            int retryCount,
            int requiredRetry,
            long costMs,
            Map<String, Object> outputForNext,
            long ts,
            String resourceHint,
            String nodeInput,
            String failedSkillName,
            String missingToolSlot,
            String missingCapability,
            int planVersion,
            String sessionId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("planId", planId);
        payload.put("phaseId", phaseId);
        payload.put("nodeId", nodeId);
        payload.put("status", status);
        payload.put("message", message);

        payload.put("planVersion", planVersion);
        payload.put("sessionId", sessionId == null ? "" : sessionId);

        payload.put("skillName", skillName);
        payload.put("declaredNodeType", declaredNodeType);
        payload.put("nodeType", declaredNodeType);

        payload.put("attempt", retryCount);
        payload.put("retryCount", retryCount);
        payload.put("requiredRetry", requiredRetry);

        payload.put("failedSkillName", failedSkillName == null ? "" : failedSkillName);

        payload.put("missingToolSlot", missingToolSlot == null ? "" : missingToolSlot);
        payload.put("missingCapability", missingCapability == null ? "" : missingCapability);
        payload.put("missingTool", (missingToolSlot == null ? "" : missingToolSlot) + "|" + (missingCapability == null ? "" : missingCapability));

        payload.put("failReason", failReason == null ? "" : failReason);
        payload.put("errorCode", errorCode == null ? "" : errorCode);
        payload.put("costMs", costMs);
        payload.put("resourceHint", resourceHint == null ? "" : resourceHint);
        payload.put("nodeInput", nodeInput == null ? "" : nodeInput);
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

    private String extractSkillName(String result) {
        JsonNode n = safeNode(result);
        if (n == null) return "";
        if (n.has("skillName")) return n.get("skillName").asText("");
        return "";
    }

    private String extractMissingToolSlot(String result) {
        JsonNode n = safeNode(result);
        if (n == null) return "";
        if (n.has("missingToolSlot")) return n.get("missingToolSlot").asText("");
        return "";
    }

    private String extractMissingCapability(String result) {
        JsonNode n = safeNode(result);
        if (n == null) return "";
        if (n.has("missingCapability")) return n.get("missingCapability").asText("");
        return "";
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

    private Map<String, String> asStringMap(Object obj) {
        if (obj == null) return Collections.emptyMap();
        try {
            Map<String, Object> raw = objectMapper.convertValue(obj, new TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                out.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
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

    private List<String> remapStringListIds(List<String> rawIds, Map<String, String> idMap, String planId, String defaultPrefix) {
        if (rawIds == null || rawIds.isEmpty()) return rawIds;
        List<String> out = new ArrayList<>();
        for (String raw : rawIds) {
            out.add(resolveMappedOrScopedId(idMap, planId, raw, defaultPrefix));
        }
        return out;
    }

    private String resolveMappedOrScopedId(Map<String, String> idMap, String planId, String rawId, String defaultPrefix) {
        String v = rawId == null ? "" : rawId.trim();
        if (v.isBlank()) {
            return normalizeScopedId(planId, defaultPrefix + "-" + SnowflakeIdUtil.nextIdStr());
        }
        if (idMap != null && idMap.containsKey(v)) {
            return idMap.get(v);
        }
        return normalizeScopedId(planId, v);
    }

    private String normalizeScopedId(String planId, String rawId) {
        String rid = rawId == null ? "" : rawId.trim();
        if (rid.isBlank()) {
            return planId + ":" + SnowflakeIdUtil.nextIdStr();
        }
        if (rid.startsWith(planId + ":")) {
            return rid;
        }
        return planId + ":" + rid;
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

    private void logNodeStart(
            String planId,
            String phaseId,
            int phaseOrder,
            String nodeId,
            String nodeName,
            String declaredNodeType,
            int retryCount,
            int maxRetry
    ) {
        log.info(
                "plan node 开始执行, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, retryCount={}/{}, status=RUNNING",
                planId, phaseId, phaseOrder, nodeId, nodeName, declaredNodeType, retryCount, maxRetry
        );
    }

    private void logNodeFailure(
            String planId,
            String phaseId,
            int phaseOrder,
            String nodeId,
            String nodeName,
            String declaredNodeType,
            int retryCount,
            int maxRetry,
            long costMs,
            String failReason,
            String errorCode
    ) {
        log.error(
                "plan node 执行失败, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, retryCount={}/{}, costMs={}, failReason={}, errorCode={}",
                planId,
                phaseId,
                phaseOrder,
                nodeId,
                nodeName,
                declaredNodeType,
                retryCount,
                maxRetry,
                costMs,
                failReason == null ? "" : failReason,
                errorCode == null ? "" : errorCode
        );
    }

    private void logPotentialToolSkillMismatch(
            String planId,
            String phaseId,
            int phaseOrder,
            String nodeId,
            String nodeName,
            String declaredNodeType,
            String errorCode,
            String failReason
    ) {
        if ("TOOL".equalsIgnoreCase(declaredNodeType) && "SKILL_CONFIG_INVALID".equalsIgnoreCase(errorCode)) {
            log.warn(
                    "plan node 声明类型与实际执行链路疑似不一致, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, actualErrorCode={}, actualFailReason={}, hint=节点声明为 TOOL，但失败码来自 SKILL 配置校验，请检查资源路由命中结果与 skill 配置(toolSlots)",
                    planId,
                    phaseId,
                    phaseOrder,
                    nodeId,
                    nodeName,
                    declaredNodeType,
                    errorCode == null ? "" : errorCode,
                    failReason == null ? "" : failReason
            );
        }
    }

    private void logInvalidSkillConfig(
            String planId,
            String phaseId,
            int phaseOrder,
            String nodeId,
            String nodeName,
            String declaredNodeType,
            String errorCode,
            String failReason,
            String resourceHint,
            String nodeInput
    ) {
        if ("SKILL".equalsIgnoreCase(declaredNodeType) && "SKILL_CONFIG_INVALID".equalsIgnoreCase(errorCode)) {
            log.error(
                    "plan skill 配置异常, planId={}, phaseId={}, phaseOrder={}, nodeId={}, nodeName={}, declaredNodeType={}, errorCode={}, failReason={}, resourceHint={}, nodeInput={}, hint=请检查 mcp_skills.tool_slots 是否为空、required_capabilities 与 tool_slots 能力是否一致、thought_chain 长度是否与 tool_slots 一致",
                    planId,
                    phaseId,
                    phaseOrder,
                    nodeId,
                    nodeName,
                    declaredNodeType,
                    errorCode == null ? "" : errorCode,
                    failReason == null ? "" : failReason,
                    resourceHint == null ? "" : resourceHint,
                    nodeInput == null ? "" : nodeInput
            );
        }
    }
}
