package org.yilena.luna.service.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.service.BlueprintValidationService;

import java.util.*;

/**
 * 蓝图校验实现
 */
@Service
public class BlueprintValidationServiceImpl implements BlueprintValidationService {

    private static final Set<String> VALID_NODE_TYPES = Set.of( // 定义方法签名
            "ANALYZE", "TOOL", "SKILL", "VALIDATE", "SUMMARIZE", "REPORT", "CODE", // 执行当前逻辑
            "PROMPT", "RESOURCE", "WORKFLOW" // 执行当前逻辑
    ); // 执行语句逻辑

    private static final Set<String> VALID_RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH"); // 定义方法签名

    @Override // 声明注解
    public String validate(Map<String, Object> blueprint) { // 定义方法签名
        if (blueprint == null) return "blueprint 不能为空"; // 进行条件判断

        if (isBlank(asStr(blueprint.get("planId")))) return "planId 不能为空"; // 进行条件判断
        if (isBlank(asStr(blueprint.get("sessionId")))) return "sessionId 不能为空"; // 进行条件判断
        if (isBlank(asStr(blueprint.get("userGoal")))) return "userGoal 不能为空"; // 进行条件判断

        Object phasesObj = blueprint.get("phases"); // 执行赋值操作
        if (!(phasesObj instanceof List<?> phases) || phases.isEmpty()) { // 进行条件判断
            return "phases 不能为空且至少包含一个阶段"; // 返回处理结果
        } // 结束当前代码块

        Set<String> phaseIds = new HashSet<>(); // 执行赋值操作
        Set<Integer> phaseOrders = new HashSet<>(); // 执行赋值操作
        for (Object p : phases) { // 执行循环处理
            if (!(p instanceof Map<?, ?> m)) return "phases 元素必须为对象"; // 进行条件判断

            String phaseId = asStr(m.get("phaseId")); // 执行赋值操作
            String name = asStr(m.get("name")); // 执行赋值操作
            String objective = asStr(m.get("objective")); // 执行赋值操作
            Integer order = asInt(m.get("phaseOrder")); // 执行赋值操作

            if (isBlank(phaseId)) return "phaseId 不能为空"; // 进行条件判断
            if (isBlank(name)) return "phase.name 不能为空"; // 进行条件判断
            if (isBlank(objective)) return "phase.objective 不能为空"; // 进行条件判断
            if (order == null || order < 1) return "phase.phaseOrder 必须为正整数"; // 进行条件判断

            if (!phaseIds.add(phaseId)) return "phaseId 重复: " + phaseId; // 进行条件判断
            if (!phaseOrders.add(order)) return "phaseOrder 重复: " + order; // 进行条件判断
        } // 结束当前代码块

        Object nodesObj = blueprint.get("nodes"); // 执行赋值操作
        if (!(nodesObj instanceof List<?> nodes) || nodes.isEmpty()) { // 进行条件判断
            return "nodes 不能为空且至少包含一个节点"; // 返回处理结果
        } // 结束当前代码块

        Set<String> nodeIds = new HashSet<>(); // 执行赋值操作
        for (Object n : nodes) { // 执行循环处理
            if (!(n instanceof Map<?, ?> m)) return "nodes 元素必须为对象"; // 进行条件判断

            String nodeId = asStr(m.get("nodeId")); // 执行赋值操作
            String phaseId = asStr(m.get("phaseId")); // 执行赋值操作
            String nodeType = asStr(m.get("nodeType")); // 执行赋值操作
            String riskLevel = asStr(m.get("riskLevel")); // 执行赋值操作

            if (isBlank(nodeId)) return "nodeId 不能为空"; // 进行条件判断
            if (!nodeIds.add(nodeId)) return "nodeId 重复: " + nodeId; // 进行条件判断
            if (isBlank(phaseId)) return "node.phaseId 不能为空"; // 进行条件判断
            if (!phaseIds.contains(phaseId)) return "node.phaseId 不存在: " + phaseId; // 进行条件判断

            if (isBlank(nodeType) || !VALID_NODE_TYPES.contains(nodeType.toUpperCase(Locale.ROOT))) { // 进行条件判断
                return "nodeType 非法: " + nodeType; // 返回处理结果
            } // 结束当前代码块

            if (isBlank(riskLevel) || !VALID_RISK_LEVELS.contains(riskLevel.toUpperCase(Locale.ROOT))) { // 进行条件判断
                return "riskLevel 非法: " + riskLevel; // 返回处理结果
            } // 结束当前代码块
        } // 结束当前代码块

        Object edgesObj = blueprint.get("edges"); // 执行赋值操作
        if (edgesObj instanceof List<?> edges) { // 进行条件判断
            for (Object e : edges) { // 执行循环处理
                if (!(e instanceof Map<?, ?> m)) return "edges 元素必须为对象"; // 进行条件判断
                String from = asStr(m.get("fromNodeId")); // 执行赋值操作
                String to = asStr(m.get("toNodeId")); // 执行赋值操作
                if (isBlank(from) || isBlank(to)) return "edge.fromNodeId/toNodeId 不能为空"; // 进行条件判断
                if (!nodeIds.contains(from)) return "edge.fromNodeId 不存在: " + from; // 进行条件判断
                if (!nodeIds.contains(to)) return "edge.toNodeId 不存在: " + to; // 进行条件判断
                if (from.equals(to)) return "edge 不允许自环: " + from; // 进行条件判断
            } // 结束当前代码块
        } // 结束当前代码块

        return null; // 返回处理结果
    } // 结束当前代码块

    private String asStr(Object o) { // 定义方法签名
        return o == null ? "" : String.valueOf(o).trim(); // 返回处理结果
    } // 结束当前代码块

    private Integer asInt(Object o) { // 定义方法签名
        try { // 尝试执行核心逻辑
            if (o == null) return null; // 进行条件判断
            if (o instanceof Number n) return n.intValue(); // 进行条件判断
            return Integer.parseInt(String.valueOf(o).trim()); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return null; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private boolean isBlank(String s) { // 定义方法签名
        return s == null || s.isBlank(); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
