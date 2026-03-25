package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.*;
import org.yilena.luna.enums.*;
import org.yilena.luna.mapper.*;
import org.yilena.luna.service.*;
import org.yilena.luna.tools.*;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenClaw 计划编排服务实现（Master Planner 版）
 *
 * 职责划分：
 * - 本类负责计划生命周期管理：创建、持久化、状态流转、报告生成
 * - 阶段内节点调度委托给 {@link PhaseExecutionService}
 * - 事件推送通过 {@link PlanEventTools} 统一处理
 *
 * 执行流程：
 * 1. 接收用户目标 -> 调用 MasterPlanningService 生成全局蓝图
 * 2. 校验蓝图 -> 物化 Phase/Node/Edge 到数据库
 * 3. 按阶段顺序调用 PhaseExecutionService 执行
 * 4. 全部阶段完成后调用 PlanReportTools 生成报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanOrchestratorServiceImpl implements PlanOrchestratorService {

    // ---- 默认值 ----
    private static final int DEFAULT_MAX_RETRY = 1;

    // ---- Mapper ----
    private final ObjectMapper objectMapper;
    private final PlanInstanceMapper planInstanceMapper;
    private final PlanNodeMapper planNodeMapper;
    private final PlanPhaseMapper planPhaseMapper;
    private final PlanEdgeMapper planEdgeMapper;

    // ---- Tools ----
    private final PlanBlueprintTools planBlueprintTools;
    private final PlanNodeTools planNodeTools;
    private final PlanEventTools planEventTools;
    private final PlanReportTools planReportTools;

    // ---- Services ----
    private final MasterPlanningService masterPlanningService;
    private final BlueprintValidationService blueprintValidationService;
    private final PhaseExecutionService phaseExecutionService;

    // =========================================================
    // 1. 创建并执行计划（完整生命周期入口）
    // =========================================================

    @Override
    public String createAndRunPlan(String sessionId, String userGoal) {
        String planId = null;
        try {
            // --- 输入校验 ---
            if (sessionId == null || sessionId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "sessionId 不能为空");
            }
            if (userGoal == null || userGoal.isBlank()) {
                return error("PLAN_INVALID_INPUT", "userGoal 不能为空");
            }

            planId = "plan-" + SnowflakeIdUtil.nextIdStr();
            int planVersion = 1;

            log.info("[Plan] 创建计划, planId={}, sessionId={}, userGoal={}", planId, sessionId, userGoal);

            // --- 持久化计划实例 ---
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

            emitPlanEvent(planId, "", "", "PLAN_CREATED", "INFO",
                    Map.of("planId", planId, "sessionId", sessionId, "userGoal", userGoal,
                            "planVersion", planVersion, "status", "PENDING",
                            "message", "计划已创建", "timestamp", System.currentTimeMillis()));

            // --- 全局规划（BigModel 一次性任务）---
            log.info("[Plan] 调用 MasterPlanningService 生成全局蓝图, planId={}", planId);
            Map<String, Object> blueprint = masterPlanningService.generateBlueprint(planId, sessionId, userGoal);

            // --- 蓝图校验 ---
            String validateErr = blueprintValidationService.validate(blueprint);
            if (validateErr != null) {
                log.error("[Plan] 蓝图校验失败, planId={}, err={}", planId, validateErr);
                updatePlanStatus(planId, PlanStatus.FAILED, validateErr);
                emitPlanFinished(planId, "FAILED", validateErr);
                return error("PLAN_BLUEPRINT_INVALID", validateErr);
            }

            // --- 保存蓝图 ---
            String saveResult = planBlueprintTools.savePlanBlueprint(
                    planId, planVersion,
                    objectMapper.writeValueAsString(blueprint),
                    "master-planner-code",
                    LocalDateTime.now().toString()
            );
            if (isError(saveResult)) {
                log.error("[Plan] 保存蓝图失败, planId={}, result={}", planId, saveResult);
                markPlanFailed(planId, "保存蓝图失败");
                emitPlanFinished(planId, "FAILED", "保存蓝图失败");
                return saveResult;
            }

            // --- 物化 Phase / Node / Edge ---
            materializePhasesAndNodes(planId, blueprint);
            buildEdgesFromBlueprint(planId, blueprint);

            updatePlanStatus(planId, PlanStatus.RUNNING, null);

            // --- 按阶段顺序执行 ---
            List<PlanPhase> orderedPhases = loadOrderedPhases(planId);
            if (orderedPhases.isEmpty()) {
                log.error("[Plan] 未找到可执行阶段, planId={}", planId);
                markPlanFailed(planId, "未找到可执行阶段");
                emitPlanFinished(planId, "FAILED", "未找到可执行阶段");
                return error("PLAN_PHASE_EMPTY", "未找到可执行阶段");
            }

            log.info("[Plan] 开始按阶段执行, planId={}, phaseCount={}", planId, orderedPhases.size());

            List<Map<String, Object>> phaseResults = new ArrayList<>();
            boolean hasPhaseFailure = false;

            for (PlanPhase phase : orderedPhases) {
                String phaseId = phase.getPhaseId();
                int phaseOrder = phase.getPhaseOrder() == null ? 0 : phase.getPhaseOrder();

                log.info("[Plan] 开始阶段, planId={}, phaseId={}, phaseOrder={}, name={}",
                        planId, phaseId, phaseOrder, phase.getName());

                markPhaseStatus(phase, PlanPhaseStatus.RUNNING, true, false);

                emitPlanEvent(planId, phaseId, "", "PLAN_PHASE_STARTED", "INFO",
                        Map.of("planId", planId, "phaseId", phaseId, "phaseOrder", phaseOrder,
                                "status", "RUNNING", "message", "阶段开始执行",
                                "timestamp", System.currentTimeMillis()));

                // 委托给 PhaseExecutionService 执行
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
                                    "status", "FAILED", "message", "阶段执行失败",
                                    "timestamp", System.currentTimeMillis()));

                    log.error("[Plan] 阶段执行失败，终止计划, planId={}, phaseId={}", planId, phaseId);
                    break;
                } else {
                    markPhaseStatus(phase, PlanPhaseStatus.SUCCESS, false, true);

                    emitPlanEvent(planId, phaseId, "", "PLAN_PHASE_FINISHED", "INFO",
                            Map.of("planId", planId, "phaseId", phaseId, "phaseOrder", phaseOrder,
                                    "status", "SUCCESS", "message", "阶段执行成功",
                                    "timestamp", System.currentTimeMillis()));

                    log.info("[Plan] 阶段执行成功, planId={}, phaseId={}, phaseOrder={}", planId, phaseId, phaseOrder);
                }
            }

            // --- 收尾报告（无论成功失败都执行）---
            String reportResult = finalizeAndReport(planId);

            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("planId", planId);
            merged.put("phaseResults", phaseResults);
            merged.put("reportResult", safeParse(reportResult));

            if (hasPhaseFailure) {
                updatePlanStatus(planId, PlanStatus.FAILED, "阶段执行失败");
                emitPlanFinished(planId, "FAILED", "阶段执行失败，已生成报告");
                merged.put("status", "error");
                merged.put("message", "计划阶段执行失败，已生成报告");
            } else {
                updatePlanStatus(planId, PlanStatus.SUCCESS, null);
                emitPlanFinished(planId, "SUCCESS", "计划执行成功并生成报告");
                merged.put("status", "success");
                merged.put("message", "计划多阶段执行成功并生成报告");
            }

            log.info("[Plan] 计划执行完毕, planId={}, status={}", planId, merged.get("status"));
            return objectMapper.writeValueAsString(merged);

        } catch (Exception e) {
            log.error("[Plan] createAndRunPlan 发生未捕获异常, planId={}, err={}", planId, e.getMessage(), e);
            if (planId != null) {
                updatePlanStatus(planId, PlanStatus.FAILED, "创建并执行计划失败: " + e.getMessage());
                emitPlanFinished(planId, "FAILED", "创建并执行计划失败: " + e.getMessage());
            }
            return error("PLAN_CREATE_RUN_FAILED", "创建并执行计划失败: " + e.getMessage());
        }
    }

    // =========================================================
    // 2. 执行单阶段（外部可单独调用）
    // =========================================================

    @Override
    public String runPhase(String planId, String phaseId) {
        try {
            if (planId == null || planId.isBlank() || phaseId == null || phaseId.isBlank()) {
                return error("PHASE_INVALID_INPUT", "planId 和 phaseId 不能为空");
            }

            PlanPhase phase = planPhaseMapper.selectById(phaseId);
            if (phase == null || !planId.equals(phase.getPlanId())) {
                return error("PHASE_NOT_FOUND", "阶段不存在或不属于该计划");
            }

            String sessionId = resolveSessionId(planId);
            log.info("[Plan] 单独执行阶段, planId={}, phaseId={}, sessionId={}", planId, phaseId, sessionId);

            markPhaseStatus(phase, PlanPhaseStatus.RUNNING, true, false);
            String result = phaseExecutionService.executePhase(planId, phase, sessionId);

            if (isError(result)) {
                markPhaseStatus(phase, PlanPhaseStatus.FAILED, false, true);
            } else {
                markPhaseStatus(phase, PlanPhaseStatus.SUCCESS, false, true);
            }

            return result;
        } catch (Exception e) {
            log.error("[Plan] runPhase 异常, planId={}, phaseId={}, err={}", planId, phaseId, e.getMessage(), e);
            return error("PHASE_EXECUTION_FAILED", "阶段执行失败: " + e.getMessage());
        }
    }

    // =========================================================
    // 3. 收尾并生成报告
    // =========================================================

    @Override
    public String finalizeAndReport(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能为空");
            }

            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) {
                return error("PLAN_NOT_FOUND", "计划不存在: " + planId);
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

            log.info("[Plan] 生成报告, planId={}, finalStatus={}, nodeTotal={}, success={}, failed={}, skipped={}",
                    planId, finalStatusText, nodes.size(), success, failed, skipped);

            String html = buildReportHtml(instance, phases, nodes, finalStatusText);
            String writeResult = planReportTools.writeHtmlReportFile(
                    planId, html, planId + ".html", "./data/reports"
            );
            if (isError(writeResult)) {
                log.error("[Plan] 写入报告文件失败, planId={}, result={}", planId, writeResult);
                return writeResult;
            }

            Map<String, Object> writePayload = extractDataPayload(writeResult);
            String reportPath = asText(writePayload.get("reportPath"));
            String reportUrl = asText(writePayload.get("reportUrl"));

            // 尝试打开浏览器（失败不中断）
            String openFlag = "FAILED";
            try {
                String openResult = planReportTools.openBrowserWithFile(reportPath);
                Map<String, Object> openPayload = extractDataPayload(openResult);
                openFlag = asText(openPayload.getOrDefault("openResult", "FAILED"));
            } catch (Exception e) {
                log.warn("[Plan] 打开浏览器失败（不中断）, planId={}, err={}", planId, e.getMessage());
            }

            // 更新计划最终状态
            instance.setFinalStatus(finalStatus);
            instance.setFinishedAt(LocalDateTime.now());
            instance.setStatus(PlanFinalStatus.SUCCESS.equals(finalStatus) ? PlanStatus.SUCCESS : PlanStatus.FAILED);
            planInstanceMapper.updateById(instance);

            // 推送报告就绪事件
            emitPlanEvent(planId, "", "", "PLAN_REPORT_READY", "INFO",
                    Map.of("planId", planId, "status", "SUCCESS",
                            "message", "任务报告已生成",
                            "reportPath", reportPath, "reportUrl", reportUrl,
                            "openResult", openFlag,
                            "timestamp", System.currentTimeMillis()));

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

            log.info("[Plan] 报告生成完毕, planId={}, reportPath={}, openResult={}", planId, reportPath, openFlag);
            return objectMapper.writeValueAsString(out);

        } catch (Exception e) {
            log.error("[Plan] finalizeAndReport 异常, planId={}, err={}", planId, e.getMessage(), e);
            return error("PLAN_REPORT_FAILED", "报告生成失败: " + e.getMessage());
        }
    }

    // =========================================================
    // 4. 获取计划可视化图谱
    // =========================================================

    @Override
    public String getPlanGraph(String planId) {
        try {
            if (planId == null || planId.isBlank()) {
                return error("PLAN_INVALID_INPUT", "planId 不能为空");
            }

            PlanInstance instance = planInstanceMapper.selectById(planId);
            if (instance == null) {
                return error("PLAN_NOT_FOUND", "计划不存在: " + planId);
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
                `outputForNext` 的写入已在上方。现在继续完成 `graph.put("nodes", ...)` 之后的剩余部分，以及所有辅助方法。

src\main\java\org\yilena\luna\service\impl\PlanOrchestratorServiceImpl.java
