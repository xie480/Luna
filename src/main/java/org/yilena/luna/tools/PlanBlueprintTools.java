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
import org.yilena.luna.entity.PlanBlueprint;
import org.yilena.luna.mapper.PlanBlueprintMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PlanBlueprint 相关工具：
 * - save_plan_blueprint
 * - load_plan_blueprint
 */
@Slf4j
@Component
public class PlanBlueprintTools extends BaseTool {

    private final PlanBlueprintMapper planBlueprintMapper;

    public PlanBlueprintTools(ObjectMapper objectMapper, PlanBlueprintMapper planBlueprintMapper) {
        super(objectMapper);
        this.planBlueprintMapper = planBlueprintMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = "tool", action = "save_plan_blueprint", content = "保存规划蓝图")
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
    @LunaLogRecord(module = "tool", action = "load_plan_blueprint", content = "加载规划蓝图")
    public String loadPlanBlueprint(
            @RequestParam("planId") String planId,
            @RequestParam(value = "planVersion", required = false) Integer planVersion
    ) {
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

            if (row == null) return error("未找到蓝图");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planId", row.getPlanId());
            out.put("planVersion", row.getPlanVersion());
            out.put("blueprintJson", row.getBlueprintJson());
            out.put("source", "db");
            log.info("load_plan_blueprint 完成, planId={}, version={}", row.getPlanId(), row.getPlanVersion());
            return success(out);
        } catch (Exception e) {
            log.error("load_plan_blueprint 失败", e);
            return error("load_plan_blueprint 失败: " + e.getMessage());
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
