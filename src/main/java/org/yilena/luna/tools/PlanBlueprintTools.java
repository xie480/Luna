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

    private final PlanBlueprintMapper planBlueprintMapper;
    private final PlanEventTools planEventTools;

    public PlanBlueprintTools(
            ObjectMapper objectMapper,
            PlanBlueprintMapper planBlueprintMapper,
            PlanEventTools planEventTools
    ) {
        super(objectMapper);
        this.planBlueprintMapper = planBlueprintMapper;
        this.planEventTools = planEventTools;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_KNOWLEDGE, type = LogType.TOOL_CALL, content = "保存规划蓝图")
    public String savePlanBlueprint(
            @RequestParam("planId") String planId,
            @RequestParam("planVersion") Integer planVersion,
            @RequestParam("blueprintJson") String blueprintJson,
            @RequestParam(value = "generatedByModel", required = false) String generatedByModel,
            @RequestParam(value = "generatedAt", required = false) String generatedAt
    ) {
        try {
            if (isBlank(planId) || planVersion == null || isBlank(blueprintJson)) {
                return error("planId, planVersion, blueprintJson 必填");
            }

            Map<String, Object> blueprint = objectMapper.readValue(blueprintJson, new TypeReference<>() {});
            LambdaQueryWrapper<PlanBlueprint> q = new LambdaQueryWrapper<PlanBlueprint>()
                    .eq(PlanBlueprint::getPlanId, planId)
                    .eq(PlanBlueprint::getPlanVersion, planVersion);
            PlanBlueprint existing = planBlueprintMapper.selectOne(q);

            PlanBlueprint entity = PlanBlueprint.builder()
                    .planId(planId)
                    .planVersion(planVersion)
                    .blueprintJson(blueprint)
                    .generatedByModel(generatedByModel)
                    .generatedAt(parseDateTime(generatedAt))
                    .build();

            if (existing == null) {
                planBlueprintMapper.insert(entity);
            } else {
                entity.setId(existing.getId());
                planBlueprintMapper.updateById(entity);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", planId);
            out.put("planVersion", planVersion);
            out.put("savedToDb", true);
            out.put("savedToRedis", false);
            log.info("save_plan_blueprint 完成, planId={}, version={}", planId, planVersion);
            return success(out);
        } catch (Exception e) {
            log.error("save_plan_blueprint 失败", e);
            return error("save_plan_blueprint 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_KNOWLEDGE, type = LogType.TOOL_CALL, content = "加载并校验规划蓝图")
    public String loadPlanBlueprint(
            @RequestParam("planId") String planId,
            @RequestParam(value = "planVersion", required = false) Integer planVersion
    ) {
        String traceId = UUID.randomUUID().toString();
        try {
            if (isBlank(planId)) return error("planId 必填");

            PlanBlueprint row;
            if (planVersion != null) {
                row = planBlueprintMapper.selectOne(new LambdaQueryWrapper<PlanBlueprint>()
                        .eq(PlanBlueprint::getPlanId, planId)
                        .eq(PlanBlueprint::getPlanVersion, planVersion)
                        .last("LIMIT 1"));
            } else {
                row = planBlueprintMapper.selectOne(new LambdaQueryWrapper<PlanBlueprint>()
                        .eq(PlanBlueprint::getPlanId, planId)
                        .orderByDesc(PlanBlueprint::getPlanVersion)
                        .last("LIMIT 1"));
            }

            if (row == null) {
                Map<String, Object> payload = Map.of(
                        "planId", planId,
                        "planVersion", planVersion == null ? 0 : planVersion,
                        "message", "未找到蓝图"
                );
                emitAuditAndSse(planId, "WARN", "PLAN_BLUEPRINT_INVALID", payload, traceId);
                return error("未找到蓝图");
            }

            Map<String, Object> blueprint = row.getBlueprintJson() == null ? Map.of() : row.getBlueprintJson();
            List<String> validationErrors = validateBlueprint(blueprint);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", row.getPlanId());
            out.put("planVersion", row.getPlanVersion());
            out.put("blueprintJson", blueprint);
            out.put("source", "db");

            if (!validationErrors.isEmpty()) {
                out.put("validationPassed", false);
                out.put("validationErrors", validationErrors);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("planId", row.getPlanId());
                payload.put("planVersion", row.getPlanVersion());
                payload.put("validationPassed", false);
                payload.put("validationErrors", validationErrors);
                emitAuditAndSse(planId, "WARN", "PLAN_BLUEPRINT_INVALID", payload, traceId);

                log.warn("load_plan_blueprint 校验失败, planId={}, version={}, errors={}",
                        row.getPlanId(), row.getPlanVersion(), validationErrors);
                return error("蓝图校验失败: " + String.join("; ", validationErrors));
            }

            out.put("validationPassed", true);
            out.put("validationErrors", List.of());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", row.getPlanId());
            payload.put("planVersion", row.getPlanVersion());
            payload.put("validationPassed", true);
            payload.put("phaseCount", asListOfMap(blueprint.get("phases")).size());
            payload.put("nodeCount", asListOfMap(blueprint.get("nodes")).size());
            emitAuditAndSse(planId, "INFO", "PLAN_BLUEPRINT_VALIDATED", payload, traceId);

            log.info("load_plan_blueprint 完成, planId={}, version={}", row.getPlanId(), row.getPlanVersion());
            return success(out);
        } catch (Exception e) {
            log.error("load_plan_blueprint 失败", e);
            Map<String, Object> payload = Map.of(
                    "planId", planId == null ? "" : planId,
                    "planVersion", planVersion == null ? 0 : planVersion,
                    "message", e.getMessage() == null ? "" : e.getMessage()
            );
            emitAuditAndSse(planId, "ERROR", "PLAN_BLUEPRINT_INVALID", payload, traceId);
            return error("load_plan_blueprint 失败: " + e.getMessage());
        }
    }

    private void emitAuditAndSse(String planId, String level, String eventType, Map<String, Object> payload, String traceId) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            planEventTools.recordPlanAuditLog(
                    planId == null ? "" : planId,
                    null,
                    null,
                    level == null ? "INFO" : level,
                    eventType == null ? "PLAN_REPORT_READY" : eventType,
                    payloadJson,
                    traceId
            );
            planEventTools.emitPlanEventSse(
                    "default",
                    eventType == null ? "PLAN_REPORT_READY" : eventType,
                    payloadJson
            );
        } catch (Exception e) {
            log.warn("emitAuditAndSse 失败（不中断） planId={}, eventType={}, err={}", planId, eventType, e.getMessage());
        }
    }

    private List<String> validateBlueprint(Map<String, Object> blueprint) {
        List<String> errors = new ArrayList<>();
        if (blueprint == null || blueprint.isEmpty()) {
            errors.add("blueprintJson 为空");
            return errors;
        }

        List<Map<String, Object>> phases = asListOfMap(blueprint.get("phases"));
        List<Map<String, Object>> nodes = asListOfMap(blueprint.get("nodes"));
        List<Map<String, Object>> edges = asListOfMap(blueprint.get("edges"));

        if (phases.isEmpty()) {
            errors.add("phases 不能为空");
        }
        if (nodes.isEmpty()) {
            errors.add("nodes 不能为空");
        }

        Set<String> phaseIds = phases.stream()
                .map(p -> text(p.get("phaseId")))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        for (Map<String, Object> p : phases) {
            if (text(p.get("name")).isBlank()) {
                errors.add("phase.name 不能为空");
            }
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        for (Map<String, Object> n : nodes) {
            String nodeId = text(n.get("nodeId"));
            if (nodeId.isBlank()) {
                errors.add("node.nodeId 不能为空");
                continue;
            }
            if (!nodeIds.add(nodeId)) {
                errors.add("nodeId 重复: " + nodeId);
            }
            if (text(n.get("name")).isBlank()) {
                errors.add("node.name 不能为空: " + nodeId);
            }

            String phaseId = text(n.get("phaseId"));
            if (!phaseId.isBlank() && !phaseIds.isEmpty() && !phaseIds.contains(phaseId)) {
                errors.add("node.phaseId 不存在: nodeId=" + nodeId + ", phaseId=" + phaseId);
            }

            List<String> deps = asStringList(n.get("dependencies"));
            if (deps != null) {
                for (String dep : deps) {
                    if (dep != null && dep.equals(nodeId)) {
                        errors.add("node 依赖自身: " + nodeId);
                    }
                }
            }
        }

        for (Map<String, Object> e : edges) {
            String from = text(e.get("fromNodeId"));
            String to = text(e.get("toNodeId"));
            if (from.isBlank() || to.isBlank()) {
                errors.add("edge fromNodeId/toNodeId 不能为空");
                continue;
            }
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) {
                errors.add("edge 引用了不存在节点: " + from + " -> " + to);
            }
            if (from.equals(to)) {
                errors.add("edge 存在自环: " + from);
            }
        }

        if (errors.isEmpty() && !isDag(nodes, edges)) {
            errors.add("nodes/edges 存在环，非有效 DAG");
        }

        return errors;
    }

    private boolean isDag(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();

        for (Map<String, Object> n : nodes) {
            String id = text(n.get("nodeId"));
            if (id.isBlank()) continue;
            graph.putIfAbsent(id, new ArrayList<>());
            indegree.putIfAbsent(id, 0);
        }

        for (Map<String, Object> n : nodes) {
            String id = text(n.get("nodeId"));
            if (id.isBlank()) continue;
            List<String> deps = asStringList(n.get("dependencies"));
            if (deps == null) continue;
            for (String dep : deps) {
                if (dep == null || dep.isBlank()) continue;
                if (!graph.containsKey(dep) || !graph.containsKey(id)) continue;
                graph.get(dep).add(id);
                indegree.put(id, indegree.getOrDefault(id, 0) + 1);
            }
        }

        for (Map<String, Object> e : edges) {
            String from = text(e.get("fromNodeId"));
            String to = text(e.get("toNodeId"));
            if (!graph.containsKey(from) || !graph.containsKey(to)) continue;
            graph.get(from).add(to);
            indegree.put(to, indegree.getOrDefault(to, 0) + 1);
        }

        Deque<String> q = new ArrayDeque<>();
        indegree.forEach((k, v) -> {
            if (v == 0) q.offer(k);
        });

        int visited = 0;
        while (!q.isEmpty()) {
            String cur = q.poll();
            visited++;
            for (String nxt : graph.getOrDefault(cur, List.of())) {
                int d = indegree.getOrDefault(nxt, 0) - 1;
                indegree.put(nxt, d);
                if (d == 0) q.offer(nxt);
            }
        }

        return visited == indegree.size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMap(Object obj) {
        if (obj == null) return List.of();
        try {
            return objectMapper.convertValue(obj, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> asStringList(Object obj) {
        if (obj == null) return null;
        try {
            List<Object> raw = objectMapper.convertValue(obj, new TypeReference<>() {});
            return raw.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String text) {
        if (isBlank(text)) return null;
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
