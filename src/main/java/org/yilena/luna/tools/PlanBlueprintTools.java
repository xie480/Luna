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
import org.yilena.luna.entity.PlanBlueprint;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.mapper.PlanBlueprintMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PlanBlueprint 相关工具：
 * - save_plan_blueprint
 * - load_plan_blueprint（已增强：内建结构校验）
 */
@Slf4j
@Component
public class PlanBlueprintTools extends BaseTool {

    private final PlanBlueprintMapper planBlueprintMapper; // 声明成员字段
    private final PlanEventTools planEventTools; // 声明成员字段

    public PlanBlueprintTools( // 定义方法签名
            ObjectMapper objectMapper, // 执行当前逻辑
            PlanBlueprintMapper planBlueprintMapper, // 执行当前逻辑
            PlanEventTools planEventTools // 执行当前逻辑
    ) { // 开始新的代码块
        super(objectMapper); // 执行语句逻辑
        this.planBlueprintMapper = planBlueprintMapper; // 执行赋值操作
        this.planEventTools = planEventTools; // 执行赋值操作
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_KNOWLEDGE, type = LogType.TOOL_CALL, content = "保存规划蓝图") // 声明注解
    public String savePlanBlueprint( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam("planVersion") Integer planVersion, // 声明注解
            @RequestParam("blueprintJson") String blueprintJson, // 声明注解
            @RequestParam(value = "generatedByModel", required = false) String generatedByModel, // 声明注解
            @RequestParam(value = "generatedAt", required = false) String generatedAt // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            if (isBlank(planId) || planVersion == null || isBlank(blueprintJson)) { // 进行条件判断
                return error("planId, planVersion, blueprintJson 必填"); // 返回处理结果
            } // 结束当前代码块

            Map<String, Object> blueprint = objectMapper.readValue(blueprintJson, new TypeReference<>() {}); // 执行赋值操作
            LambdaQueryWrapper<PlanBlueprint> q = new LambdaQueryWrapper<PlanBlueprint>() // 执行赋值操作
                    .eq(PlanBlueprint::getPlanId, planId) // 执行当前逻辑
                    .eq(PlanBlueprint::getPlanVersion, planVersion); // 执行语句逻辑
            PlanBlueprint existing = planBlueprintMapper.selectOne(q); // 执行赋值操作

            PlanBlueprint entity = PlanBlueprint.builder() // 执行赋值操作
                    .planId(planId) // 执行当前逻辑
                    .planVersion(planVersion) // 执行当前逻辑
                    .blueprintJson(blueprint) // 执行当前逻辑
                    .generatedByModel(generatedByModel) // 执行当前逻辑
                    .generatedAt(parseDateTime(generatedAt)) // 执行当前逻辑
                    .build(); // 执行语句逻辑

            if (existing == null) { // 进行条件判断
                planBlueprintMapper.insert(entity); // 执行语句逻辑
            } else { // 切换到分支逻辑
                entity.setId(existing.getId()); // 执行语句逻辑
                planBlueprintMapper.updateById(entity); // 执行语句逻辑
            } // 结束当前代码块

            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("planId", planId); // 执行语句逻辑
            out.put("planVersion", planVersion); // 执行语句逻辑
            out.put("savedToDb", true); // 执行语句逻辑
            out.put("savedToRedis", false); // 执行语句逻辑
            log.info("save_plan_blueprint 完成, planId={}, version={}", planId, planVersion); // 执行赋值操作
            return success(out); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("save_plan_blueprint 失败", e); // 执行语句逻辑
            return error("save_plan_blueprint 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_KNOWLEDGE, type = LogType.TOOL_CALL, content = "加载并校验规划蓝图") // 声明注解
    public String loadPlanBlueprint( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam(value = "planVersion", required = false) Integer planVersion // 声明注解
    ) { // 开始新的代码块
        String traceId = UUID.randomUUID().toString(); // 执行赋值操作
        try { // 尝试执行核心逻辑
            if (isBlank(planId)) return error("planId 必填"); // 进行条件判断

            PlanBlueprint row; // 执行语句逻辑
            if (planVersion != null) { // 进行条件判断
                row = planBlueprintMapper.selectOne(new LambdaQueryWrapper<PlanBlueprint>() // 执行赋值操作
                        .eq(PlanBlueprint::getPlanId, planId) // 执行当前逻辑
                        .eq(PlanBlueprint::getPlanVersion, planVersion) // 执行当前逻辑
                        .last("LIMIT 1")); // 执行语句逻辑
            } else { // 切换到分支逻辑
                row = planBlueprintMapper.selectOne(new LambdaQueryWrapper<PlanBlueprint>() // 执行赋值操作
                        .eq(PlanBlueprint::getPlanId, planId) // 执行当前逻辑
                        .orderByDesc(PlanBlueprint::getPlanVersion) // 执行当前逻辑
                        .last("LIMIT 1")); // 执行语句逻辑
            } // 结束当前代码块

            if (row == null) { // 进行条件判断
                Map<String, Object> payload = Map.of( // 执行赋值操作
                        "planId", planId, // 执行当前逻辑
                        "planVersion", planVersion == null ? 0 : planVersion, // 执行赋值操作
                        "message", "未找到蓝图" // 执行当前逻辑
                ); // 执行语句逻辑
                emitAuditAndSse(planId, "WARN", "PLAN_BLUEPRINT_INVALID", payload, traceId); // 执行语句逻辑
                return error("未找到蓝图"); // 返回处理结果
            } // 结束当前代码块

            Map<String, Object> blueprint = row.getBlueprintJson() == null ? Map.of() : row.getBlueprintJson(); // 执行赋值操作
            List<String> validationErrors = validateBlueprint(blueprint); // 执行赋值操作

            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("planId", row.getPlanId()); // 执行语句逻辑
            out.put("planVersion", row.getPlanVersion()); // 执行语句逻辑
            out.put("blueprintJson", blueprint); // 执行语句逻辑
            out.put("source", "db"); // 执行语句逻辑

            if (!validationErrors.isEmpty()) { // 进行条件判断
                out.put("validationPassed", false); // 执行语句逻辑
                out.put("validationErrors", validationErrors); // 执行语句逻辑

                Map<String, Object> payload = new LinkedHashMap<>(); // 执行赋值操作
                payload.put("planId", row.getPlanId()); // 执行语句逻辑
                payload.put("planVersion", row.getPlanVersion()); // 执行语句逻辑
                payload.put("validationPassed", false); // 执行语句逻辑
                payload.put("validationErrors", validationErrors); // 执行语句逻辑
                emitAuditAndSse(planId, "WARN", "PLAN_BLUEPRINT_INVALID", payload, traceId); // 执行语句逻辑

                log.warn("load_plan_blueprint 校验失败, planId={}, version={}, errors={}", // 执行赋值操作
                        row.getPlanId(), row.getPlanVersion(), validationErrors); // 执行语句逻辑
                return error("蓝图校验失败: " + String.join("; ", validationErrors)); // 返回处理结果
            } // 结束当前代码块

            out.put("validationPassed", true); // 执行语句逻辑
            out.put("validationErrors", List.of()); // 执行语句逻辑

            Map<String, Object> payload = new LinkedHashMap<>(); // 执行赋值操作
            payload.put("planId", row.getPlanId()); // 执行语句逻辑
            payload.put("planVersion", row.getPlanVersion()); // 执行语句逻辑
            payload.put("validationPassed", true); // 执行语句逻辑
            payload.put("phaseCount", asListOfMap(blueprint.get("phases")).size()); // 执行语句逻辑
            payload.put("nodeCount", asListOfMap(blueprint.get("nodes")).size()); // 执行语句逻辑
            emitAuditAndSse(planId, "INFO", "PLAN_BLUEPRINT_VALIDATED", payload, traceId); // 执行语句逻辑

            log.info("load_plan_blueprint 完成, planId={}, version={}", row.getPlanId(), row.getPlanVersion()); // 执行赋值操作
            return success(out); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("load_plan_blueprint 失败", e); // 执行语句逻辑
            Map<String, Object> payload = Map.of( // 执行赋值操作
                    "planId", planId == null ? "" : planId, // 执行赋值操作
                    "planVersion", planVersion == null ? 0 : planVersion, // 执行赋值操作
                    "message", e.getMessage() == null ? "" : e.getMessage() // 执行赋值操作
            ); // 执行语句逻辑
            emitAuditAndSse(planId, "ERROR", "PLAN_BLUEPRINT_INVALID", payload, traceId); // 执行语句逻辑
            return error("load_plan_blueprint 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private void emitAuditAndSse(String planId, String level, String eventType, Map<String, Object> payload, String traceId) { // 定义方法签名
        try { // 尝试执行核心逻辑
            String payloadJson = objectMapper.writeValueAsString(payload == null ? Map.of() : payload); // 执行赋值操作
            planEventTools.recordPlanAuditLog( // 执行当前逻辑
                    planId == null ? "" : planId, // 执行赋值操作
                    null, // 执行当前逻辑
                    null, // 执行当前逻辑
                    level == null ? "INFO" : level, // 执行赋值操作
                    eventType == null ? "PLAN_REPORT_READY" : eventType, // 执行赋值操作
                    payloadJson, // 执行当前逻辑
                    traceId // 执行当前逻辑
            ); // 执行语句逻辑
            planEventTools.emitPlanEventSse( // 执行当前逻辑
                    "default", // 执行当前逻辑
                    eventType == null ? "PLAN_REPORT_READY" : eventType, // 执行赋值操作
                    payloadJson // 执行当前逻辑
            ); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.warn("emitAuditAndSse 失败（不中断） planId={}, eventType={}, err={}", planId, eventType, e.getMessage()); // 执行赋值操作
        } // 结束当前代码块
    } // 结束当前代码块

    private List<String> validateBlueprint(Map<String, Object> blueprint) { // 定义方法签名
        List<String> errors = new ArrayList<>(); // 执行赋值操作
        if (blueprint == null || blueprint.isEmpty()) { // 进行条件判断
            errors.add("blueprintJson 为空"); // 执行语句逻辑
            return errors; // 返回处理结果
        } // 结束当前代码块

        List<Map<String, Object>> phases = asListOfMap(blueprint.get("phases")); // 执行赋值操作
        List<Map<String, Object>> nodes = asListOfMap(blueprint.get("nodes")); // 执行赋值操作
        List<Map<String, Object>> edges = asListOfMap(blueprint.get("edges")); // 执行赋值操作

        if (phases.isEmpty()) { // 进行条件判断
            errors.add("phases 不能为空"); // 执行语句逻辑
        } // 结束当前代码块
        if (nodes.isEmpty()) { // 进行条件判断
            errors.add("nodes 不能为空"); // 执行语句逻辑
        } // 结束当前代码块

        Set<String> phaseIds = phases.stream() // 执行赋值操作
                .map(p -> text(p.get("phaseId"))) // 执行当前逻辑
                .filter(s -> !s.isBlank()) // 执行当前逻辑
                .collect(Collectors.toSet()); // 执行语句逻辑
        for (Map<String, Object> p : phases) { // 执行循环处理
            if (text(p.get("name")).isBlank()) { // 进行条件判断
                errors.add("phase.name 不能为空"); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        Set<String> nodeIds = new LinkedHashSet<>(); // 执行赋值操作
        for (Map<String, Object> n : nodes) { // 执行循环处理
            String nodeId = text(n.get("nodeId")); // 执行赋值操作
            if (nodeId.isBlank()) { // 进行条件判断
                errors.add("node.nodeId 不能为空"); // 执行语句逻辑
                continue; // 执行语句逻辑
            } // 结束当前代码块
            if (!nodeIds.add(nodeId)) { // 进行条件判断
                errors.add("nodeId 重复: " + nodeId); // 执行语句逻辑
            } // 结束当前代码块
            if (text(n.get("name")).isBlank()) { // 进行条件判断
                errors.add("node.name 不能为空: " + nodeId); // 执行语句逻辑
            } // 结束当前代码块

            String phaseId = text(n.get("phaseId")); // 执行赋值操作
            if (!phaseId.isBlank() && !phaseIds.isEmpty() && !phaseIds.contains(phaseId)) { // 进行条件判断
                errors.add("node.phaseId 不存在: nodeId=" + nodeId + ", phaseId=" + phaseId); // 执行赋值操作
            } // 结束当前代码块

            List<String> deps = asStringList(n.get("dependencies")); // 执行赋值操作
            if (deps != null) { // 进行条件判断
                for (String dep : deps) { // 执行循环处理
                    if (dep != null && dep.equals(nodeId)) { // 进行条件判断
                        errors.add("node 依赖自身: " + nodeId); // 执行语句逻辑
                    } // 结束当前代码块
                } // 结束当前代码块
            } // 结束当前代码块
        } // 结束当前代码块

        for (Map<String, Object> e : edges) { // 执行循环处理
            String from = text(e.get("fromNodeId")); // 执行赋值操作
            String to = text(e.get("toNodeId")); // 执行赋值操作
            if (from.isBlank() || to.isBlank()) { // 进行条件判断
                errors.add("edge fromNodeId/toNodeId 不能为空"); // 执行语句逻辑
                continue; // 执行语句逻辑
            } // 结束当前代码块
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) { // 进行条件判断
                errors.add("edge 引用了不存在节点: " + from + " -> " + to); // 执行语句逻辑
            } // 结束当前代码块
            if (from.equals(to)) { // 进行条件判断
                errors.add("edge 存在自环: " + from); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        if (errors.isEmpty() && !isDag(nodes, edges)) { // 进行条件判断
            errors.add("nodes/edges 存在环，非有效 DAG"); // 执行语句逻辑
        } // 结束当前代码块

        return errors; // 返回处理结果
    } // 结束当前代码块

    private boolean isDag(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) { // 定义方法签名
        Map<String, List<String>> graph = new LinkedHashMap<>(); // 执行赋值操作
        Map<String, Integer> indegree = new LinkedHashMap<>(); // 执行赋值操作

        for (Map<String, Object> n : nodes) { // 执行循环处理
            String id = text(n.get("nodeId")); // 执行赋值操作
            if (id.isBlank()) continue; // 进行条件判断
            graph.putIfAbsent(id, new ArrayList<>()); // 执行语句逻辑
            indegree.putIfAbsent(id, 0); // 执行语句逻辑
        } // 结束当前代码块

        for (Map<String, Object> n : nodes) { // 执行循环处理
            String id = text(n.get("nodeId")); // 执行赋值操作
            if (id.isBlank()) continue; // 进行条件判断
            List<String> deps = asStringList(n.get("dependencies")); // 执行赋值操作
            if (deps == null) continue; // 进行条件判断
            for (String dep : deps) { // 执行循环处理
                if (dep == null || dep.isBlank()) continue; // 进行条件判断
                if (!graph.containsKey(dep) || !graph.containsKey(id)) continue; // 进行条件判断
                graph.get(dep).add(id); // 执行语句逻辑
                indegree.put(id, indegree.getOrDefault(id, 0) + 1); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        for (Map<String, Object> e : edges) { // 执行循环处理
            String from = text(e.get("fromNodeId")); // 执行赋值操作
            String to = text(e.get("toNodeId")); // 执行赋值操作
            if (!graph.containsKey(from) || !graph.containsKey(to)) continue; // 进行条件判断
            graph.get(from).add(to); // 执行语句逻辑
            indegree.put(to, indegree.getOrDefault(to, 0) + 1); // 执行语句逻辑
        } // 结束当前代码块

        Deque<String> q = new ArrayDeque<>(); // 执行赋值操作
        indegree.forEach((k, v) -> { // 开始新的代码块
            if (v == 0) q.offer(k); // 进行条件判断
        }); // 执行语句逻辑

        int visited = 0; // 执行赋值操作
        while (!q.isEmpty()) { // 执行循环判断
            String cur = q.poll(); // 执行赋值操作
            visited++; // 执行语句逻辑
            for (String nxt : graph.getOrDefault(cur, List.of())) { // 执行循环处理
                int d = indegree.getOrDefault(nxt, 0) - 1; // 执行赋值操作
                indegree.put(nxt, d); // 执行语句逻辑
                if (d == 0) q.offer(nxt); // 进行条件判断
            } // 结束当前代码块
        } // 结束当前代码块

        return visited == indegree.size(); // 返回处理结果
    } // 结束当前代码块

    @SuppressWarnings("unchecked") // 声明注解
    private List<Map<String, Object>> asListOfMap(Object obj) { // 定义方法签名
        if (obj == null) return List.of(); // 进行条件判断
        try { // 尝试执行核心逻辑
            return objectMapper.convertValue(obj, new TypeReference<>() {}); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return List.of(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private List<String> asStringList(Object obj) { // 定义方法签名
        if (obj == null) return null; // 进行条件判断
        try { // 尝试执行核心逻辑
            List<Object> raw = objectMapper.convertValue(obj, new TypeReference<>() {}); // 执行赋值操作
            return raw.stream().filter(Objects::nonNull).map(String::valueOf).toList(); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return null; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private static LocalDateTime parseDateTime(String text) { // 定义方法签名
        if (isBlank(text)) return null; // 进行条件判断
        try { // 尝试执行核心逻辑
            return LocalDateTime.parse(text); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return null; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String text(Object o) { // 定义方法签名
        return o == null ? "" : String.valueOf(o).trim(); // 返回处理结果
    } // 结束当前代码块

    private static boolean isBlank(String s) { // 定义方法签名
        return s == null || s.isBlank(); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
