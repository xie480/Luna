package org.yilena.luna.service.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.service.BlueprintValidationService;

import java.util.*;

/**
 * 蓝图校验实现
 */
@Service
public class BlueprintValidationServiceImpl implements BlueprintValidationService {

    private static final Set<String> VALID_NODE_TYPES = Set.of(
            "ANALYZE", "TOOL", "SKILL", "VALIDATE", "SUMMARIZE", "REPORT", "CODE",
            "PROMPT", "RESOURCE", "WORKFLOW"
    );

    private static final Set<String> VALID_RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    @Override
    public String validate(Map<String, Object> blueprint) {
        if (blueprint == null) return "blueprint 不能为空";

        if (isBlank(asStr(blueprint.get("planId")))) return "planId 不能为空";
        if (isBlank(asStr(blueprint.get("sessionId")))) return "sessionId 不能为空";
        if (isBlank(asStr(blueprint.get("userGoal")))) return "userGoal 不能为空";

        Object phasesObj = blueprint.get("phases");
        if (!(phasesObj instanceof List<?> phases) || phases.isEmpty()) {
            return "phases 不能为空且至少包含一个阶段";
        }

        Set<String> phaseIds = new HashSet<>();
        Set<Integer> phaseOrders = new HashSet<>();
        for (Object p : phases) {
            if (!(p instanceof Map<?, ?> m)) return "phases 元素必须为对象";

            String phaseId = asStr(m.get("phaseId"));
            String name = asStr(m.get("name"));
            String objective = asStr(m.get("objective"));
            Integer order = asInt(m.get("phaseOrder"));

            if (isBlank(phaseId)) return "phaseId 不能为空";
            if (isBlank(name)) return "phase.name 不能为空";
            if (isBlank(objective)) return "phase.objective 不能为空";
            if (order == null || order < 1) return "phase.phaseOrder 必须为正整数";

            if (!phaseIds.add(phaseId)) return "phaseId 重复: " + phaseId;
            if (!phaseOrders.add(order)) return "phaseOrder 重复: " + order;
        }

        Object nodesObj = blueprint.get("nodes");
        if (!(nodesObj instanceof List<?> nodes) || nodes.isEmpty()) {
            return "nodes 不能为空且至少包含一个节点";
        }

        Set<String> nodeIds = new HashSet<>();
        for (Object n : nodes) {
            if (!(n instanceof Map<?, ?> m)) return "nodes 元素必须为对象";

            String nodeId = asStr(m.get("nodeId"));
            String phaseId = asStr(m.get("phaseId"));
            String nodeType = asStr(m.get("nodeType"));
            String riskLevel = asStr(m.get("riskLevel"));

            if (isBlank(nodeId)) return "nodeId 不能为空";
            if (!nodeIds.add(nodeId)) return "nodeId 重复: " + nodeId;
            if (isBlank(phaseId)) return "node.phaseId 不能为空";
            if (!phaseIds.contains(phaseId)) return "node.phaseId 不存在: " + phaseId;

            if (isBlank(nodeType) || !VALID_NODE_TYPES.contains(nodeType.toUpperCase(Locale.ROOT))) {
                return "nodeType 非法: " + nodeType;
            }

            if (isBlank(riskLevel) || !VALID_RISK_LEVELS.contains(riskLevel.toUpperCase(Locale.ROOT))) {
                return "riskLevel 非法: " + riskLevel;
            }
        }

        Object edgesObj = blueprint.get("edges");
        if (edgesObj instanceof List<?> edges) {
            for (Object e : edges) {
                if (!(e instanceof Map<?, ?> m)) return "edges 元素必须为对象";
                String from = asStr(m.get("fromNodeId"));
                String to = asStr(m.get("toNodeId"));
                if (isBlank(from) || isBlank(to)) return "edge.fromNodeId/toNodeId 不能为空";
                if (!nodeIds.contains(from)) return "edge.fromNodeId 不存在: " + from;
                if (!nodeIds.contains(to)) return "edge.toNodeId 不存在: " + to;
                if (from.equals(to)) return "edge 不允许自环: " + from;
            }
        }

        return null;
    }

    private String asStr(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private Integer asInt(Object o) {
        try {
            if (o == null) return null;
            if (o instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
