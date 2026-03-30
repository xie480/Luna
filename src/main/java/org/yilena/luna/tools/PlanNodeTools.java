package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.PlanNodeStatus;
import org.yilena.luna.mapper.PlanNodeMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PlanNode 相关工具：
 * - list_phase_nodes
 * - update_node_status
 * - append_node_output
 * - query_plan_progress
 * - aggregate_phase_outputs
 * - replan_failed_nodes
 */
@Slf4j
@Component
public class PlanNodeTools extends BaseTool {

    private final PlanNodeMapper planNodeMapper;
    private final PlanEventTools planEventTools;

    public PlanNodeTools(
            ObjectMapper objectMapper,
            PlanNodeMapper planNodeMapper,
            PlanEventTools planEventTools
    ) {
        super(objectMapper);
        this.planNodeMapper = planNodeMapper;
        this.planEventTools = planEventTools;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "列出阶段节点")
    public String listPhaseNodes(@RequestParam("planId") String planId, @RequestParam("phaseId") String phaseId) {
        try {
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId)
                    .eq(PlanNode::getPhaseId, phaseId)
                    .orderByAsc(PlanNode::getNodeId));
            log.info("list_phase_nodes 完成, planId={}, phaseId={}, size={}", planId, phaseId, nodes.size());
            return success(nodes);
        } catch (Exception e) {
            log.error("list_phase_nodes 失败", e);
            return error("list_phase_nodes 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "更新节点状态")
    public String updateNodeStatus(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("status") String status,
            @RequestParam(value = "costMs", required = false) Long costMs,
            @RequestParam(value = "failReason", required = false) String failReason,
            @RequestParam(value = "retryCount", required = false) Integer retryCount
    ) {
        try {
            PlanNode node = findByPlanIdAndNodeId(planId, nodeId);
            if (node == null) return error("节点不存在");

            node.setStatus(PlanNodeStatus.valueOf(status.toUpperCase()));
            if (costMs != null) node.setCostMs(costMs);
            if (failReason != null) node.setFailReason(failReason);
            if (retryCount != null) node.setRetryCount(retryCount);

            if (node.getStatus() == PlanNodeStatus.RUNNING && node.getStartedAt() == null) {
                node.setStartedAt(LocalDateTime.now());
            }
            if (node.getStatus() == PlanNodeStatus.SUCCESS || node.getStatus() == PlanNodeStatus.FAILED || node.getStatus() == PlanNodeStatus.SKIPPED) {
                node.setFinishedAt(LocalDateTime.now());
            }

            planNodeMapper.updateById(node);
            log.info("update_node_status 完成, planId={}, nodeId={}, status={}", planId, nodeId, node.getStatus().getValue());
            return success(Map.of("updated", true, "nodeId", nodeId, "status", node.getStatus().getValue()));
        } catch (Exception e) {
            log.error("update_node_status 失败", e);
            return error("update_node_status 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "追加节点输出")
    public String appendNodeOutput(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("outputJson") String outputJson,
            @RequestParam(value = "outputForNext", required = false) String outputForNext
    ) {
        try {
            PlanNode node = findByPlanIdAndNodeId(planId, nodeId);
            if (node == null) return error("节点不存在");

            Map<String, Object> out = objectMapper.readValue(outputJson, new TypeReference<>() {});
            Map<String, Object> next = null;
            if (outputForNext != null && !outputForNext.isBlank()) {
                next = objectMapper.readValue(outputForNext, new TypeReference<>() {});
            }

            node.setOutputJson(out);
            node.setOutputForNext(next);
            planNodeMapper.updateById(node);

            int size = outputJson.getBytes(StandardCharsets.UTF_8).length;
            log.info("append_node_output 完成, planId={}, nodeId={}, size={}", planId, nodeId, size);
            return success(Map.of("saved", true, "outputSizeBytes", size));
        } catch (Exception e) {
            log.error("append_node_output 失败", e);
            return error("append_node_output 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "查询计划进度")
    public String queryPlanProgress(@RequestParam("planId") String planId) {
        try {
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId));
            Map<String, Long> stats = nodes.stream().collect(Collectors.groupingBy(
                    n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(),
                    Collectors.counting()
            ));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", planId);
            out.put("nodeStats", stats);
            out.put("total", nodes.size());
            log.info("query_plan_progress 完成, planId={}, total={}", planId, nodes.size());
            return success(out);
        } catch (Exception e) {
            log.error("query_plan_progress 失败", e);
            return error("query_plan_progress 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "聚合阶段输出")
    public String aggregatePhaseOutputs(
            @RequestParam("planId") String planId,
            @RequestParam("phaseId") String phaseId,
            @RequestParam(value = "summaryNodeId", required = false) String summaryNodeId
    ) {
        try {
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId)
                    .eq(PlanNode::getPhaseId, phaseId)
                    .orderByAsc(PlanNode::getNodeId));

            List<PlanNode> validNodes = nodes.stream()
                    .filter(n -> n.getOutputForNext() != null && !n.getOutputForNext().isEmpty())
                    .filter(n -> n.getStatus() != PlanNodeStatus.SKIPPED)
                    .toList();

            List<Map<String, Object>> outputs = new ArrayList<>();
            for (PlanNode n : validNodes) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("nodeId", n.getNodeId());
                one.put("nodeType", n.getNodeType());
                one.put("status", n.getStatus() == null ? null : n.getStatus().getValue());
                one.put("outputForNext", n.getOutputForNext());
                outputs.add(one);
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("planId", planId);
            summary.put("phaseId", phaseId);
            summary.put("totalNodes", nodes.size());
            summary.put("validOutputNodes", validNodes.size());
            summary.put("outputs", outputs);

            if (summaryNodeId != null && !summaryNodeId.isBlank()) {
                PlanNode summaryNode = findByPlanIdAndNodeId(planId, summaryNodeId);
                if (summaryNode == null) {
                    return error("summaryNodeId 对应节点不存在");
                }
                summaryNode.setOutputForNext(summary);
                planNodeMapper.updateById(summaryNode);
            }

            emitAuditAndSse(
                    planId,
                    phaseId,
                    null,
                    "INFO",
                    "PLAN_PHASE_FINISHED",
                    Map.of(
                            "planId", planId,
                            "phaseId", phaseId,
                            "action", "aggregate_phase_outputs",
                            "totalNodes", nodes.size(),
                            "validOutputNodes", validNodes.size(),
                            "summaryNodeId", summaryNodeId == null ? "" : summaryNodeId
                    )
            );

            return success(summary);
        } catch (Exception e) {
            log.error("aggregate_phase_outputs 失败", e);
            return error("aggregate_phase_outputs 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "失败节点重规划分析")
    public String replanFailedNodes(@RequestParam("planId") String planId) {
        try {
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId));

            List<PlanNode> failed = nodes.stream()
                    .filter(n -> n.getStatus() == PlanNodeStatus.FAILED)
                    .toList();

            List<Map<String, Object>> failedNodes = new ArrayList<>();
            for (PlanNode n : failed) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("nodeId", n.getNodeId());
                one.put("phaseId", n.getPhaseId());
                one.put("nodeType", n.getNodeType());
                one.put("failReason", n.getFailReason());
                one.put("retryCount", n.getRetryCount());
                one.put("maxRetry", n.getMaxRetry());
                failedNodes.add(one);

                Map<String, Object> replanAdvice = new LinkedHashMap<>();
                replanAdvice.put("action", "REPLAN_ADVICE");
                replanAdvice.put("nodeId", n.getNodeId());
                replanAdvice.put("suggestions", List.of(
                        "优先重试可重试节点（retryCount < maxRetry）",
                        "对工具错误尝试替换工具或降级方案",
                        "非关键路径失败节点可评估跳过"
                ));
                replanAdvice.put("generatedAt", LocalDateTime.now().toString());

                Map<String, Object> merged = new LinkedHashMap<>();
                if (n.getOutputForNext() != null) {
                    merged.putAll(n.getOutputForNext());
                }
                merged.put("replan", replanAdvice);
                n.setOutputForNext(merged);
                planNodeMapper.updateById(n);
            }

            Map<String, Long> stats = nodes.stream().collect(Collectors.groupingBy(
                    n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(),
                    Collectors.counting()
            ));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", planId);
            out.put("nodeStats", stats);
            out.put("failedCount", failed.size());
            out.put("failedNodes", failedNodes);
            out.put("suggestions", List.of(
                    "优先重试可重试节点（retryCount < maxRetry）",
                    "对工具错误节点尝试替换工具或降级方案",
                    "对非关键路径失败节点可评估 SKIP 继续"
            ));

            emitAuditAndSse(
                    planId,
                    null,
                    null,
                    "WARN",
                    "PLAN_REPLANNED",
                    Map.of(
                            "planId", planId,
                            "failedCount", failed.size(),
                            "nodeStats", stats
                    )
            );

            return success(out);
        } catch (Exception e) {
            log.error("replan_failed_nodes 失败", e);
            return error("replan_failed_nodes 失败: " + e.getMessage());
        }
    }

    private void emitAuditAndSse(
            String planId,
            String phaseId,
            String nodeId,
            String level,
            String eventType,
            Map<String, Object> payload
    ) {
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            planEventTools.recordPlanAuditLog(planId, phaseId, nodeId, level, eventType, json, UUID.randomUUID().toString());
            planEventTools.emitPlanEventSse("default", eventType, json);
        } catch (Exception e) {
            log.warn("emitAuditAndSse 失败（不中断） planId={}, eventType={}, err={}", planId, eventType, e.getMessage());
        }
    }

    private PlanNode findByPlanIdAndNodeId(String planId, String nodeId) {
        if (planId == null || planId.isBlank() || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        return planNodeMapper.selectOne(new LambdaQueryWrapper<PlanNode>()
                .eq(PlanNode::getPlanId, planId)
                .eq(PlanNode::getNodeId, nodeId)
                .last("LIMIT 1"));
    }
}
