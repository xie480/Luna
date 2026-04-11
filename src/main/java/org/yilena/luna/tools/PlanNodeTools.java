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
/**
 * 计划节点工具类，负责查询节点、更新节点执行状态、沉淀节点输出并汇总阶段级执行结果。
 */
public class PlanNodeTools extends BaseTool {

    /**
     * 计划节点数据访问对象，用于读写节点执行状态和产出。
     */
    private final PlanNodeMapper planNodeMapper;
    /**
     * 计划事件工具，用于在关键节点流程后发送审计和 SSE 事件。
     */
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
    /**
     * 查询指定计划阶段下的全部节点，按节点标识排序返回。
     */
    public String listPhaseNodes(@RequestParam("planId") String planId, @RequestParam("phaseId") String phaseId) {
        try {
            /**
             * 按计划和阶段读取节点清单，为阶段执行或展示提供完整节点视图。
             */
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
    /**
     * 更新节点执行状态，并在需要时补齐开始时间、结束时间和失败信息。
     */
    public String updateNodeStatus(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("status") String status,
            @RequestParam(value = "costMs", required = false) Long costMs,
            @RequestParam(value = "failReason", required = false) String failReason,
            @RequestParam(value = "retryCount", required = false) Integer retryCount
    ) {
        try {
            /**
             * 先定位目标节点，再基于新状态更新耗时、失败原因和重试次数等执行元数据。
             */
            PlanNode node = findByPlanIdAndNodeId(planId, nodeId);
            if (node == null) return error("节点不存在");

            node.setStatus(PlanNodeStatus.valueOf(status.toUpperCase()));
            if (costMs != null) node.setCostMs(costMs);
            if (failReason != null) node.setFailReason(failReason);
            if (retryCount != null) node.setRetryCount(retryCount);

            /**
             * 根据状态流转补齐开始和结束时间，保证节点生命周期信息完整可追踪。
             */
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
    /**
     * 保存节点输出和传递给后续节点的结构化结果，供阶段串联与结果汇总使用。
     */
    public String appendNodeOutput(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("outputJson") String outputJson,
            @RequestParam(value = "outputForNext", required = false) String outputForNext
    ) {
        try {
            /**
             * 先校验目标节点存在，再解析输出 JSON，确保入库内容结构可靠。
             */
            PlanNode node = findByPlanIdAndNodeId(planId, nodeId);
            if (node == null) return error("节点不存在");

            Map<String, Object> out = objectMapper.readValue(outputJson, new TypeReference<>() {});
            Map<String, Object> next = null;
            if (outputForNext != null && !outputForNext.isBlank()) {
                next = objectMapper.readValue(outputForNext, new TypeReference<>() {});
            }

            /**
             * 节点原始输出和供下游复用的输出分开保存，兼顾回放和链路透传。
             */
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
    /**
     * 统计计划整体进度，按节点状态汇总当前计划的执行分布。
     */
    public String queryPlanProgress(@RequestParam("planId") String planId) {
        try {
            /**
             * 拉取计划全部节点后按状态分组计数，形成当前执行进度快照。
             */
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
    /**
     * 汇总阶段内节点产出，并可选择写回摘要节点，作为下一阶段的输入基础。
     */
    public String aggregatePhaseOutputs(
            @RequestParam("planId") String planId,
            @RequestParam("phaseId") String phaseId,
            @RequestParam(value = "summaryNodeId", required = false) String summaryNodeId
    ) {
        try {
            /**
             * 先读取阶段内全部节点，再筛出有下游输出且未跳过的有效节点。
             */
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId)
                    .eq(PlanNode::getPhaseId, phaseId)
                    .orderByAsc(PlanNode::getNodeId));

            List<PlanNode> validNodes = nodes.stream()
                    .filter(n -> n.getOutputForNext() != null && !n.getOutputForNext().isEmpty())
                    .filter(n -> n.getStatus() != PlanNodeStatus.SKIPPED)
                    .toList();

            /**
             * 将可复用的节点输出整理为统一结构，便于阶段汇总和后续节点消费。
             */
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

            /**
             * 指定摘要节点时，将阶段汇总结果回写到该节点，作为后续链路输入。
             */
            if (summaryNodeId != null && !summaryNodeId.isBlank()) {
                PlanNode summaryNode = findByPlanIdAndNodeId(planId, summaryNodeId);
                if (summaryNode == null) {
                    return error("summaryNodeId 对应节点不存在");
                }
                summaryNode.setOutputForNext(summary);
                planNodeMapper.updateById(summaryNode);
            }

            /**
             * 汇总完成后发送阶段完成事件，通知外部链路可以进入下一阶段。
             */
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
    /**
     * 汇总失败节点并生成重规划建议，为计划恢复或局部补救提供输入。
     */
    public String replanFailedNodes(@RequestParam("planId") String planId) {
        try {
            /**
             * 先读取计划全部节点，再筛出失败节点作为重规划分析对象。
             */
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>()
                    .eq(PlanNode::getPlanId, planId));

            List<PlanNode> failed = nodes.stream()
                    .filter(n -> n.getStatus() == PlanNodeStatus.FAILED)
                    .toList();

            List<Map<String, Object>> failedNodes = new ArrayList<>();
            /**
             * 为每个失败节点生成重规划建议，并写回到节点输出供后续链路消费。
             */
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

            /**
             * 重规划建议生成后发出告警事件，提醒上层关注失败节点和恢复策略。
             */
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

    /**
     * 统一发送节点相关审计日志和 SSE 事件，失败时只记录告警不打断主流程。
     */
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

    /**
     * 按计划标识和节点标识查询单个节点，找不到时返回 null。
     */
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
