package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.enums.PlanNodeStatus;
import org.yilena.luna.mapper.PlanNodeMapper;
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
 * OpenClaw 计划编排服务实现（MVP）
 *
 * 设计目标：
 * 1) 提供“可执行、可观测、可收尾”的最小闭环；
 * 2) 在原单阶段基础上，支持“最低限度多阶段执行”；
 * 3) 保持日志充分，便于问题定位与复盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanOrchestratorServiceImpl implements PlanOrchestratorService {

    private static final String DEFAULT_PHASE_ID = "phase-1";

    private final ObjectMapper objectMapper;
    private final PlanNodeMapper planNodeMapper;

    // 复用现有 Tool 实现（最小入侵）
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

            log.info("开始创建计划, planId={}, sessionId={}, userGoal={}", planId, sessionId, userGoal);

            // 1) 构造最小蓝图（现支持多阶段）
            Map<String, Object> blueprint = buildMvpBlueprint(planId, sessionId, userGoal);

            // 2) 蓝图结构校验
            String validateErr = validateBlueprint(blueprint);
            if (validateErr != null) {
                log.warn("蓝图校验失败, planId={}, err={}", planId, validateErr);
                return error("PLAN_BLUEPRINT_INVALID", validateErr);
            }

            // 3) 保存蓝图
            String saveResult = planBlueprintTools.savePlanBlueprint(
                    planId,
                    planVersion,
                    objectMapper.writeValueAsString(blueprint),
                    "mvp-local-planner",
                    LocalDateTime.now().toString()
            );
            if (isError(saveResult)) {
                log.error("保存蓝图失败, planId={}, saveResult={}", planId, saveResult);
                return saveResult;
            }

            // 4) 初始化阶段节点（最低限度多阶段：每个阶段一个占位节点）
            List<String> phaseIds = extractPhaseIds(blueprint);
            if (phaseIds.isEmpty()) {
                phaseIds = List.of(DEFAULT_PHASE_ID);
            }

            for (String phaseId : phaseIds) {
                PlanNode node = PlanNode.builder()
                        .nodeId("node-" + SnowflakeIdUtil.nextIdStr())
                        .planId(planId)
                        .phaseId(phaseId)
                        .name("mvp-node-" + phaseId)
                        .status(PlanNodeStatus.PENDING)
                        .retryCount(0)
                        .maxRetry(0)
                        .build();
                planNodeMapper.insert(node);

                emitEvent("PLAN_CREATED", Map.of(
                        "planId", planId,
                        "phaseId", phaseId,
                        "nodeId", node.getNodeId(),
                        "status", "PENDING",
                        "message", "阶段节点已创建",
                        "timestamp", System.currentTimeMillis()
                ));
            }

            // 5) 多阶段顺序执行（最低限度方案）
            List<Map<String, Object>> phaseResults = new ArrayList<>();
            boolean hasPhaseFailure = false;

            for (String phaseId : phaseIds) {
                log.info("准备执行阶段, planId={}, phaseId={}", planId, phaseId);
                String phaseResult = runPhase(planId, phaseId);
                phaseResults.add(Map.of(
                        "phaseId", phaseId,
                        "result", safeParse(phaseResult)
                ));

                if (isError(phaseResult)) {
                    hasPhaseFailure = true;
                    log.warn("阶段执行失败，停止后续阶段, planId={}, failedPhaseId={}", planId, phaseId);
                    break;
                }
            }

            // 6) 收尾与报告（无论阶段成功失败都执行）
            String reportResult = finalizeAndReport(planId);

            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("planId", planId);
            merged.put("phaseResults", phaseResults);
            merged.put("reportResult", safeParse(reportResult));

            if (hasPhaseFailure) {
                merged.put("status", "error");
                merged.put("message", "计划阶段执行失败，已生成报告");
                return objectMapper.writeValueAsString(merged);
            }

            if (isError(reportResult)) {
                merged.put("status", "error");
                merged.put("message", "计划执行完成，但报告生成失败");
                return objectMapper.writeValueAsString(merged);
            }

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

            log.info("开始执行阶段, planId={}, phaseId={}", planId, phaseId);

            String listResult = planNodeTools.listPhaseNodes(planId, phaseId);
            if (isError(listResult)) {
                log.warn("查询阶段节点失败, planId={}, phaseId={}, result={}", planId, phaseId, listResult);
                return listResult;
            }

            List<PlanNode> nodes = planNodeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanNode>()
                            .eq(PlanNode::getPlanId, planId)
                            .eq(PlanNode::getPhaseId, phaseId)
                            .orderByAsc(PlanNode::getNodeId)
            );

            if (nodes.isEmpty()) {
                log.warn("阶段下无节点, planId={}, phaseId={}", planId, phaseId);
                return error("PHASE_EMPTY", "阶段下无可执行节点");
            }

            long phaseStart = System.currentTimeMillis();
            int successCount = 0;
            int failCount = 0;

            emitEvent("PLAN_PHASE_STARTED", Map.of(
                    "planId", planId,
                    "phaseId", phaseId,
                    "status", "RUNNING",
                    "message", "阶段开始执行",
                    "timestamp", System.currentTimeMillis()
            ));

            for (PlanNode node : nodes) {
                long nodeStart = System.currentTimeMillis();

                // 状态流转校验：仅允许从 PENDING/BLOCKED/APPROVAL_PENDING -> RUNNING
                if (!canTransitToRunning(node.getStatus())) {
                    log.warn("节点状态流转不合法，跳过执行, nodeId={}, currentStatus={}",
                            node.getNodeId(), node.getStatus() == null ? "null" : node.getStatus().getValue());
                    failCount++;
                    continue;
                }

                String running = planNodeTools.updateNodeStatus(
                        planId, node.getNodeId(), "RUNNING", null, null, node.getRetryCount()
                );
                if (isError(running)) {
                    log.error("更新节点 RUNNING 失败, nodeId={}, result={}", node.getNodeId(), running);
                    failCount++;
                    continue;
                }

                emitEvent("PLAN_NODE_RUNNING", Map.of(
                        "planId", planId,
                        "phaseId", phaseId,
                        "nodeId", node.getNodeId(),
                        "status", "RUNNING",
                        "message", "节点执行中",
                        "timestamp", System.currentTimeMillis()
                ));

                // MVP：模拟节点成功输出
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("nodeName", node.getName());
                output.put("result", "ok");
                output.put("phaseId", phaseId);

                String append = planNodeTools.appendNodeOutput(
                        planId,
                        node.getNodeId(),
                        objectMapper.writeValueAsString(output),
                        objectMapper.writeValueAsString(Map.of("result", "ok"))
                );
                if (isError(append)) {
                    String fail = planNodeTools.updateNodeStatus(
                            planId,
                            node.getNodeId(),
                            "FAILED",
                            System.currentTimeMillis() - nodeStart,
                            "append_node_output failed",
                            node.getRetryCount()
                    );
                    log.error("节点输出写入失败, nodeId={}, appendResult={}, failUpdate={}", node.getNodeId(), append, fail);

                    emitEvent("PLAN_NODE_FAILED", Map.of(
                            "planId", planId,
                            "phaseId", phaseId,
                            "nodeId", node.getNodeId(),
                            "status", "FAILED",
                            "message", "节点输出写入失败",
                            "errorCode", "NODE_OUTPUT_APPEND_FAILED",
                            "retryCount", node.getRetryCount() == null ? 0 : node.getRetryCount(),
                            "timestamp", System.currentTimeMillis()
                    ));
                    failCount++;
                    continue;
                }

                String success = planNodeTools.updateNodeStatus(
                        planId,
                        node.getNodeId(),
                        "SUCCESS",
                        System.currentTimeMillis() - nodeStart,
                        null,
                        node.getRetryCount()
                );
                if (isError(success)) {
                    log.error("更新节点 SUCCESS 失败, nodeId={}, result={}", node.getNodeId(), success);
                    failCount++;
                    continue;
                }

                emitEvent("PLAN_NODE_SUCCESS", Map.of(
                        "planId", planId,
                        "phaseId", phaseId,
                        "nodeId", node.getNodeId(),
                        "status", "SUCCESS",
                        "message", "节点执行成功",
                        "costMs", System.currentTimeMillis() - nodeStart,
                        "retryCount", node.getRetryCount() == null ? 0 : node.getRetryCount(),
                        "timestamp", System.currentTimeMillis()
                ));

                successCount++;
            }

            String progress = planNodeTools.queryPlanProgress(planId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", failCount > 0 ? "error" : "success");
            out.put("planId", planId);
            out.put("phaseId", phaseId);
            out.put("successCount", successCount);
            out.put("failCount", failCount);
            out.put("costMs", System.currentTimeMillis() - phaseStart);
            out.put("progress", safeParse(progress));

            emitEvent("PLAN_PHASE_FINISHED", Map.of(
                    "planId", planId,
                    "phaseId", phaseId,
                    "status", failCount > 0 ? "FAILED" : "SUCCESS",
                    "message", failCount > 0 ? "阶段执行结束（含失败）" : "阶段执行完成",
                    "costMs", System.currentTimeMillis() - phaseStart,
                    "timestamp", System.currentTimeMillis()
            ));

            log.info("阶段执行完成, planId={}, phaseId={}, successCount={}, failCount={}", planId, phaseId, successCount, failCount);
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

            log.info("开始收尾并生成报告, planId={}", planId);

            // 加载蓝图（用于报告展示）
            String loaded = planBlueprintTools.loadPlanBlueprint(planId, null);
            Map<String, Object> loadedObj = safeParse(loaded);

            // 读取节点
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
                log.error("写报告失败, planId={}, writeResult={}", planId, writeResult);
                return writeResult;
            }

            Map<String, Object> writeObj = safeParse(writeResult);
            Object dataObj = writeObj.get("data");
            String reportPath = "";
            if (dataObj instanceof Map<?, ?> dataMap) {
                reportPath = String.valueOf(dataMap.getOrDefault("reportPath", ""));
            }

            String openResult = "";
            if (!reportPath.isBlank()) {
                openResult = planReportTools.openBrowserWithFile(reportPath);
            }

            emitEvent("PLAN_REPORT_READY", Map.of(
                    "planId", planId,
                    "status", "SUCCESS",
                    "message", "报告已生成",
                    "reportPath", reportPath,
                    "timestamp", System.currentTimeMillis()
            ));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("planId", planId);
            out.put("finalStatus", finalStatus);
            out.put("writeResult", safeParse(writeResult));
            out.put("openResult", safeParse(openResult));

            log.info("计划报告生成完成, planId={}, finalStatus={}, reportPath={}", planId, finalStatus, reportPath);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("finalizeAndReport 失败", e);
            return error("PLAN_REPORT_FAILED", "计划报告生成失败: " + e.getMessage());
        }
    }

    /**
     * 构造 MVP 蓝图（最低限度多阶段）
     * 说明：
     * - 若用户目标包含“多阶段 / multi phase / 分阶段 / 两阶段 / 三阶段”等关键词，则生成 2 阶段；
     * - 否则默认单阶段。
     */
    private Map<String, Object> buildMvpBlueprint(String planId, String sessionId, String userGoal) {
        Map<String, Object> blueprint = new LinkedHashMap<>();
        blueprint.put("planId", planId);
        blueprint.put("sessionId", sessionId);
        blueprint.put("userGoal", userGoal);
        blueprint.put("createdAt", LocalDateTime.now().toString());

        boolean multiPhase = shouldUseMultiPhase(userGoal);

        List<Map<String, Object>> phases = new ArrayList<>();
        Map<String, Object> phase1 = new LinkedHashMap<>();
        phase1.put("phaseId", "phase-1");
        phase1.put("name", "MVP_PHASE_1");
        phase1.put("objective", "执行阶段一");
        phases.add(phase1);

        if (multiPhase) {
            Map<String, Object> phase2 = new LinkedHashMap<>();
            phase2.put("phaseId", "phase-2");
            phase2.put("name", "MVP_PHASE_2");
            phase2.put("objective", "执行阶段二");
            phases.add(phase2);
        }

        blueprint.put("phases", phases);
        return blueprint;
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

    /**
     * 蓝图基础校验
     */
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

    /**
     * 节点状态是否可流转到 RUNNING
     */
    private boolean canTransitToRunning(PlanNodeStatus status) {
        if (status == null) return true;
        return status == PlanNodeStatus.PENDING
                || status == PlanNodeStatus.BLOCKED
                || status == PlanNodeStatus.APPROVAL_PENDING;
    }

    /**
     * 生成 HTML 报告
     */
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

    /**
     * 发送计划事件到 SSE（通过 PlanEventTools）
     */
    private void emitEvent(String eventType, Map<String, Object> payload) {
        try {
            planEventTools.emitPlanEventSse("default", eventType, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("emitEvent 失败, eventType={}, err={}", eventType, e.getMessage());
        }
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
