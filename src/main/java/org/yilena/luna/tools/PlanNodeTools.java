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

    private final PlanNodeMapper planNodeMapper; // 声明成员字段
    private final PlanEventTools planEventTools; // 声明成员字段

    public PlanNodeTools( // 定义方法签名
            ObjectMapper objectMapper, // 执行当前逻辑
            PlanNodeMapper planNodeMapper, // 执行当前逻辑
            PlanEventTools planEventTools // 执行当前逻辑
    ) { // 开始新的代码块
        super(objectMapper); // 执行语句逻辑
        this.planNodeMapper = planNodeMapper; // 执行赋值操作
        this.planEventTools = planEventTools; // 执行赋值操作
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "列出阶段节点") // 声明注解
    public String listPhaseNodes(@RequestParam("planId") String planId, @RequestParam("phaseId") String phaseId) { // 定义方法签名
        try { // 尝试执行核心逻辑
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>() // 执行赋值操作
                    .eq(PlanNode::getPlanId, planId) // 执行当前逻辑
                    .eq(PlanNode::getPhaseId, phaseId) // 执行当前逻辑
                    .orderByAsc(PlanNode::getNodeId)); // 执行语句逻辑
            log.info("list_phase_nodes 完成, planId={}, phaseId={}, size={}", planId, phaseId, nodes.size()); // 执行赋值操作
            return success(nodes); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("list_phase_nodes 失败", e); // 执行语句逻辑
            return error("list_phase_nodes 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "更新节点状态") // 声明注解
    public String updateNodeStatus( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam("nodeId") String nodeId, // 声明注解
            @RequestParam("status") String status, // 声明注解
            @RequestParam(value = "costMs", required = false) Long costMs, // 声明注解
            @RequestParam(value = "failReason", required = false) String failReason, // 声明注解
            @RequestParam(value = "retryCount", required = false) Integer retryCount // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            PlanNode node = findByPlanIdAndNodeId(planId, nodeId); // 执行赋值操作
            if (node == null) return error("节点不存在"); // 进行条件判断

            node.setStatus(PlanNodeStatus.valueOf(status.toUpperCase())); // 执行语句逻辑
            if (costMs != null) node.setCostMs(costMs); // 进行条件判断
            if (failReason != null) node.setFailReason(failReason); // 进行条件判断
            if (retryCount != null) node.setRetryCount(retryCount); // 进行条件判断

            if (node.getStatus() == PlanNodeStatus.RUNNING && node.getStartedAt() == null) { // 进行条件判断
                node.setStartedAt(LocalDateTime.now()); // 执行语句逻辑
            } // 结束当前代码块
            if (node.getStatus() == PlanNodeStatus.SUCCESS || node.getStatus() == PlanNodeStatus.FAILED || node.getStatus() == PlanNodeStatus.SKIPPED) { // 进行条件判断
                node.setFinishedAt(LocalDateTime.now()); // 执行语句逻辑
            } // 结束当前代码块

            planNodeMapper.updateById(node); // 执行语句逻辑
            log.info("update_node_status 完成, planId={}, nodeId={}, status={}", planId, nodeId, node.getStatus().getValue()); // 执行赋值操作
            return success(Map.of("updated", true, "nodeId", nodeId, "status", node.getStatus().getValue())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("update_node_status 失败", e); // 执行语句逻辑
            return error("update_node_status 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "追加节点输出") // 声明注解
    public String appendNodeOutput( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam("nodeId") String nodeId, // 声明注解
            @RequestParam("outputJson") String outputJson, // 声明注解
            @RequestParam(value = "outputForNext", required = false) String outputForNext // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            PlanNode node = findByPlanIdAndNodeId(planId, nodeId); // 执行赋值操作
            if (node == null) return error("节点不存在"); // 进行条件判断

            Map<String, Object> out = objectMapper.readValue(outputJson, new TypeReference<>() {}); // 执行赋值操作
            Map<String, Object> next = null; // 执行赋值操作
            if (outputForNext != null && !outputForNext.isBlank()) { // 进行条件判断
                next = objectMapper.readValue(outputForNext, new TypeReference<>() {}); // 执行赋值操作
            } // 结束当前代码块

            node.setOutputJson(out); // 执行语句逻辑
            node.setOutputForNext(next); // 执行语句逻辑
            planNodeMapper.updateById(node); // 执行语句逻辑

            int size = outputJson.getBytes(StandardCharsets.UTF_8).length; // 执行赋值操作
            log.info("append_node_output 完成, planId={}, nodeId={}, size={}", planId, nodeId, size); // 执行赋值操作
            return success(Map.of("saved", true, "outputSizeBytes", size)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("append_node_output 失败", e); // 执行语句逻辑
            return error("append_node_output 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "查询计划进度") // 声明注解
    public String queryPlanProgress(@RequestParam("planId") String planId) { // 定义方法签名
        try { // 尝试执行核心逻辑
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>() // 执行赋值操作
                    .eq(PlanNode::getPlanId, planId)); // 执行语句逻辑
            Map<String, Long> stats = nodes.stream().collect(Collectors.groupingBy( // 执行赋值操作
                    n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(), // 执行赋值操作
                    Collectors.counting() // 执行当前逻辑
            )); // 执行语句逻辑
            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("planId", planId); // 执行语句逻辑
            out.put("nodeStats", stats); // 执行语句逻辑
            out.put("total", nodes.size()); // 执行语句逻辑
            log.info("query_plan_progress 完成, planId={}, total={}", planId, nodes.size()); // 执行赋值操作
            return success(out); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("query_plan_progress 失败", e); // 执行语句逻辑
            return error("query_plan_progress 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "聚合阶段输出") // 声明注解
    public String aggregatePhaseOutputs( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam("phaseId") String phaseId, // 声明注解
            @RequestParam(value = "summaryNodeId", required = false) String summaryNodeId // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>() // 执行赋值操作
                    .eq(PlanNode::getPlanId, planId) // 执行当前逻辑
                    .eq(PlanNode::getPhaseId, phaseId) // 执行当前逻辑
                    .orderByAsc(PlanNode::getNodeId)); // 执行语句逻辑

            List<PlanNode> validNodes = nodes.stream() // 执行赋值操作
                    .filter(n -> n.getOutputForNext() != null && !n.getOutputForNext().isEmpty()) // 执行赋值操作
                    .filter(n -> n.getStatus() != PlanNodeStatus.SKIPPED) // 执行赋值操作
                    .toList(); // 执行语句逻辑

            List<Map<String, Object>> outputs = new ArrayList<>(); // 执行赋值操作
            for (PlanNode n : validNodes) { // 执行循环处理
                Map<String, Object> one = new LinkedHashMap<>(); // 执行赋值操作
                one.put("nodeId", n.getNodeId()); // 执行语句逻辑
                one.put("nodeType", n.getNodeType()); // 执行语句逻辑
                one.put("status", n.getStatus() == null ? null : n.getStatus().getValue()); // 执行赋值操作
                one.put("outputForNext", n.getOutputForNext()); // 执行语句逻辑
                outputs.add(one); // 执行语句逻辑
            } // 结束当前代码块

            Map<String, Object> summary = new LinkedHashMap<>(); // 执行赋值操作
            summary.put("planId", planId); // 执行语句逻辑
            summary.put("phaseId", phaseId); // 执行语句逻辑
            summary.put("totalNodes", nodes.size()); // 执行语句逻辑
            summary.put("validOutputNodes", validNodes.size()); // 执行语句逻辑
            summary.put("outputs", outputs); // 执行语句逻辑

            if (summaryNodeId != null && !summaryNodeId.isBlank()) { // 进行条件判断
                PlanNode summaryNode = findByPlanIdAndNodeId(planId, summaryNodeId); // 执行赋值操作
                if (summaryNode == null) { // 进行条件判断
                    return error("summaryNodeId 对应节点不存在"); // 返回处理结果
                } // 结束当前代码块
                summaryNode.setOutputForNext(summary); // 执行语句逻辑
                planNodeMapper.updateById(summaryNode); // 执行语句逻辑
            } // 结束当前代码块

            emitAuditAndSse( // 执行当前逻辑
                    planId, // 执行当前逻辑
                    phaseId, // 执行当前逻辑
                    null, // 执行当前逻辑
                    "INFO", // 执行当前逻辑
                    "PLAN_PHASE_FINISHED", // 执行当前逻辑
                    Map.of( // 执行当前逻辑
                            "planId", planId, // 执行当前逻辑
                            "phaseId", phaseId, // 执行当前逻辑
                            "action", "aggregate_phase_outputs", // 执行当前逻辑
                            "totalNodes", nodes.size(), // 执行当前逻辑
                            "validOutputNodes", validNodes.size(), // 执行当前逻辑
                            "summaryNodeId", summaryNodeId == null ? "" : summaryNodeId // 执行赋值操作
                    ) // 执行当前逻辑
            ); // 执行语句逻辑

            return success(summary); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("aggregate_phase_outputs 失败", e); // 执行语句逻辑
            return error("aggregate_phase_outputs 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "失败节点重规划分析") // 声明注解
    public String replanFailedNodes(@RequestParam("planId") String planId) { // 定义方法签名
        try { // 尝试执行核心逻辑
            List<PlanNode> nodes = planNodeMapper.selectList(new LambdaQueryWrapper<PlanNode>() // 执行赋值操作
                    .eq(PlanNode::getPlanId, planId)); // 执行语句逻辑

            List<PlanNode> failed = nodes.stream() // 执行赋值操作
                    .filter(n -> n.getStatus() == PlanNodeStatus.FAILED) // 执行赋值操作
                    .toList(); // 执行语句逻辑

            List<Map<String, Object>> failedNodes = new ArrayList<>(); // 执行赋值操作
            for (PlanNode n : failed) { // 执行循环处理
                Map<String, Object> one = new LinkedHashMap<>(); // 执行赋值操作
                one.put("nodeId", n.getNodeId()); // 执行语句逻辑
                one.put("phaseId", n.getPhaseId()); // 执行语句逻辑
                one.put("nodeType", n.getNodeType()); // 执行语句逻辑
                one.put("failReason", n.getFailReason()); // 执行语句逻辑
                one.put("retryCount", n.getRetryCount()); // 执行语句逻辑
                one.put("maxRetry", n.getMaxRetry()); // 执行语句逻辑
                failedNodes.add(one); // 执行语句逻辑

                Map<String, Object> replanAdvice = new LinkedHashMap<>(); // 执行赋值操作
                replanAdvice.put("action", "REPLAN_ADVICE"); // 执行语句逻辑
                replanAdvice.put("nodeId", n.getNodeId()); // 执行语句逻辑
                replanAdvice.put("suggestions", List.of( // 执行当前逻辑
                        "优先重试可重试节点（retryCount < maxRetry）", // 执行当前逻辑
                        "对工具错误尝试替换工具或降级方案", // 执行当前逻辑
                        "非关键路径失败节点可评估跳过" // 执行当前逻辑
                )); // 执行语句逻辑
                replanAdvice.put("generatedAt", LocalDateTime.now().toString()); // 执行语句逻辑

                Map<String, Object> merged = new LinkedHashMap<>(); // 执行赋值操作
                if (n.getOutputForNext() != null) { // 进行条件判断
                    merged.putAll(n.getOutputForNext()); // 执行语句逻辑
                } // 结束当前代码块
                merged.put("replan", replanAdvice); // 执行语句逻辑
                n.setOutputForNext(merged); // 执行语句逻辑
                planNodeMapper.updateById(n); // 执行语句逻辑
            } // 结束当前代码块

            Map<String, Long> stats = nodes.stream().collect(Collectors.groupingBy( // 执行赋值操作
                    n -> n.getStatus() == null ? "PENDING" : n.getStatus().getValue(), // 执行赋值操作
                    Collectors.counting() // 执行当前逻辑
            )); // 执行语句逻辑

            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("planId", planId); // 执行语句逻辑
            out.put("nodeStats", stats); // 执行语句逻辑
            out.put("failedCount", failed.size()); // 执行语句逻辑
            out.put("failedNodes", failedNodes); // 执行语句逻辑
            out.put("suggestions", List.of( // 执行当前逻辑
                    "优先重试可重试节点（retryCount < maxRetry）", // 执行当前逻辑
                    "对工具错误节点尝试替换工具或降级方案", // 执行当前逻辑
                    "对非关键路径失败节点可评估 SKIP 继续" // 执行当前逻辑
            )); // 执行语句逻辑

            emitAuditAndSse( // 执行当前逻辑
                    planId, // 执行当前逻辑
                    null, // 执行当前逻辑
                    null, // 执行当前逻辑
                    "WARN", // 执行当前逻辑
                    "PLAN_REPLANNED", // 执行当前逻辑
                    Map.of( // 执行当前逻辑
                            "planId", planId, // 执行当前逻辑
                            "failedCount", failed.size(), // 执行当前逻辑
                            "nodeStats", stats // 执行当前逻辑
                    ) // 执行当前逻辑
            ); // 执行语句逻辑

            return success(out); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("replan_failed_nodes 失败", e); // 执行语句逻辑
            return error("replan_failed_nodes 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private void emitAuditAndSse( // 定义方法签名
            String planId, // 执行当前逻辑
            String phaseId, // 执行当前逻辑
            String nodeId, // 执行当前逻辑
            String level, // 执行当前逻辑
            String eventType, // 执行当前逻辑
            Map<String, Object> payload // 执行当前逻辑
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload); // 执行赋值操作
            planEventTools.recordPlanAuditLog(planId, phaseId, nodeId, level, eventType, json, UUID.randomUUID().toString()); // 执行语句逻辑
            planEventTools.emitPlanEventSse("default", eventType, json); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.warn("emitAuditAndSse 失败（不中断） planId={}, eventType={}, err={}", planId, eventType, e.getMessage()); // 执行赋值操作
        } // 结束当前代码块
    } // 结束当前代码块

    private PlanNode findByPlanIdAndNodeId(String planId, String nodeId) { // 定义方法签名
        if (planId == null || planId.isBlank() || nodeId == null || nodeId.isBlank()) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块
        return planNodeMapper.selectOne(new LambdaQueryWrapper<PlanNode>() // 返回处理结果
                .eq(PlanNode::getPlanId, planId) // 执行当前逻辑
                .eq(PlanNode::getNodeId, nodeId) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
