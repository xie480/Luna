package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.PlanNode;
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
 */
@Slf4j
@Component
public class PlanNodeTools extends BaseTool {

    private final PlanNodeMapper planNodeMapper;

    public PlanNodeTools(ObjectMapper objectMapper, PlanNodeMapper planNodeMapper) {
        super(objectMapper);
        this.planNodeMapper = planNodeMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = "tool", action = "list_phase_nodes", content = "列出阶段节点")
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
    @LunaLogRecord(module = "tool", action = "update_node_status", content = "更新节点状态")
    public String updateNodeStatus(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("status") String status,
            @RequestParam(value = "costMs", required = false) Long costMs,
            @RequestParam(value = "failReason", required = false) String failReason,
            @RequestParam(value = "retryCount", required = false) Integer retryCount
    ) {
        try {
            PlanNode node = planNodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getPlanId(), planId)) return error("节点不存在");

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
    @LunaLogRecord(module = "tool", action = "append_node_output", content = "追加节点输出")
    public String appendNodeOutput(
            @RequestParam("planId") String planId,
            @RequestParam("nodeId") String nodeId,
            @RequestParam("outputJson") String outputJson,
            @RequestParam(value = "outputForNext", required = false) String outputForNext
    ) {
        try {
            PlanNode node = planNodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getPlanId(), planId)) return error("节点不存在");

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
    @LunaLogRecord(module = "tool", action = "query_plan_progress", content = "查询计划进度")
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
}
