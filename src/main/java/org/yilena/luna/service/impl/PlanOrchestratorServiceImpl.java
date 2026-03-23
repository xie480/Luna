package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.PlanBlueprint;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.enums.PlanNodeStatus;
import org.yilena.luna.mapper.PlanBlueprintMapper;
import org.yilena.luna.mapper.PlanNodeMapper;
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
    private final PlanBlueprintMapper planBlueprintMapper;
    private final PlanNodeMapper planNodeMapper;

    // 复用现有 Tool 实现（最小入侵）
    private final PlanBlueprintTools planBlueprintTools;
    private final PlanNodeTools planNodeTools;
    private final PlanEventTools planEventTools;
    private final PlanReportTools planReportTools;

    @Override
    public String createAndRunPlan(String sessionId, String userGoal) {
        try {
            String planId = "plan-" + SnowflakeIdUtil.nextIdStr();
            int planVersion = 1;

            // MVP：构造最小蓝图（单阶段）
            Map<String, Object> blueprint = new LinkedHashMap<>();
            blueprint.put("planId", planId);
            blueprint.put("sessionId", sessionId);
            blueprint.put("userGoal", userGoal);
            blueprint.put("createdAt", LocalDateTime.now().toString());

            List<Map<String, Object>> phases = new ArrayList<>();
            Map<String, Object> phase1 = new LinkedHashMap<>();
            phase1.put("phaseId", "phase-1");
            phase1.put("name", "MVP_PHASE");
            phase1.put("objective", "执行最小闭环");
            phases.add(phase1);
            blueprint.put("phases", phases);

            // 1) 保存蓝图
            String saveResult = planBlueprintTools.savePlanBlueprint(
                    planId,
                    planVersion,
                    objectMapper.writeValueAsString(blueprint),
                    "mvp-local-planner",
                    LocalDateTime.now().toString()
            );
            if (isError(saveResult)) {
                return saveResult;
            }

            // 2) 初始化一个最小节点（演示用途）
            PlanNode node = PlanNode.builder()
                    .nodeId("node-" + SnowflakeIdUtil.nextIdStr())
                    .planId(planId)
                    .phaseId("phase-1")
                    .name("mvp-node")
                    .status(PlanNodeStatus.PENDING)
                    .retryCount(0)
                    .maxRetry(0)
                    .build();
            planNodeMapper.insert(node);

            // 3) 发事件
            emitEvent("PLAN_CREATED", Map.of(
                    "planId", planId,
                    "phaseId", "phase-1",
                    "nodeId", node.getNodeId(),
                    "status", "PENDING",
                    "message", "计划已创建",
                    "timestamp", System.currentTimeMillis()
            ));

            // 4) 运行阶段
            String phaseResult = runPhase(planId, "phase-1");
            if (isError(phaseResult)) {
                // 失败也收尾产出报告
                String reportResult = finalizeAndReport(planId);
                return mergeResult("error", "计划执行失败，已生成报告", planId, phaseResult, reportResult);
            }

            // 5) 收尾并生成报告
            String reportResult = finalizeAndReport(planId);
            return mergeResult("success", "计划执行成功并生成报告", planId, phaseResult, reportResult);
        } catch (Exception e) {
            log.error("createAndRunPlan 失败", e);
            return error("PLAN_CREATE_RUN_FAILED", "创建并执行计划失败: " + e.getMessage());
        }
    }

    @Override
    public String runPhase(String planId, String phaseId) {
        try {
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

            long phaseStart = System.currentTimeMillis();
            int successCount = 0;
            int failCount = 0;

            for (PlanNode node : nodes) {
                long nodeStart = System.currentTimeMillis();

                // RUNNING
                String running = planNodeTools.updateNodeStatus(
                        planId, node.getNodeId(), "RUNNING", null, null, node.getRetryCount()
                );
                if (isError(running)) {
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

                // MVP：模拟成功输出
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
                    planNodeTools.updateNodeStatus(
                            planId,
                            node.getNodeId(),
                            "FAILED",
                            System.currentTimeMillis() - nodeStart,
                            "append_node_output failed",
                            node.getRetryCount()
                    );
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

                // SUCCESS
                planNodeTools.updateNodeStatus(
                        planId,
                        node.getNodeId(),
                        "SUCCESS",
                        System.currentTimeMillis() - nodeStart,
                        null,
                        node.getRetryCount()
                );

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

            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("runPhase 失败", e);
            return error("PHASE_RUN_FAILED", "阶段执行失败: " + e.getMessage());
        }
    }

    @Override
    public String finalizeAndReport(String planId) {
        try {
            // 加载蓝图
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

            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset=\"UTF-8\"><title>Plan Report</title></head><body>");
            html.append("<h1>OpenClaw Plan Report</h1>");
            html.append("<p><b>planId:</b> ").append(planId).append("</p>");
            html.append("<p><b>finalStatus:</b> ").append(finalStatus).append("</p>");
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

            String writeResult = planReportTools.writeHtmlReportFile(
                    planId,
                    html.toString(),
                    planId + ".html",
                    "./data/reports"
            );
            if (isError(writeResult)) {
                return writeResult;
            }

            Map<String, Object> writeObj = safeParse(writeResult);
            String reportPath = String.valueOf(((Map<?, ?>) writeObj.getOrDefault("data", Map.of())).getOrDefault("reportPath", ""));

            String openResult = planReportTools.openBrowserWithFile(reportPath);

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
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("finalizeAndReport 失败", e);
            return error("PLAN_REPORT_FAILED", "计划报告生成失败: " + e.getMessage());
        }
    }

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

    private String mergeResult(String status, String message, String planId, String phaseResult, String reportResult) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            out.put("message", message);
            out.put("planId", planId);
            out.put("phaseResult", safeParse(phaseResult));
            out.put("reportResult", safeParse(reportResult));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return error("PLAN_MERGE_RESULT_FAILED", "结果合并失败: " + e.getMessage());
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
