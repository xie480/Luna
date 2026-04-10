package org.yilena.luna.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.ResultStatusConstants;
import org.yilena.luna.constants.SessionConstant;
import org.yilena.luna.entity.query.PlanFinalizeRequest;
import org.yilena.luna.entity.query.PlanPhaseRunRequest;
import org.yilena.luna.entity.query.PlanRunRequest;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.Map;

/**
 * 计划编排控制器，负责创建计划、执行阶段、生成报告以及查询计划图。
 */
@Slf4j
@RestController
@RequestMapping("/luna/api/plan")
@RequiredArgsConstructor
@Tag(name = "计划编排接口", description = "提供计划创建、阶段执行、收尾报告和图谱查询能力")
public class PlanOrchestratorController {

    /**
     * 错误码字段名，用于统一错误响应结构。
     */
    private static final String KEY_ERROR_CODE = "errorCode";

    /**
     * 请求参数不合法时使用的统一错误码。
     */
    private static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    /**
     * 创建并执行计划失败时使用的错误码。
     */
    private static final String ERROR_PLAN_RUN_FAILED = "PLAN_RUN_FAILED";
    /**
     * 执行单个计划阶段失败时使用的错误码。
     */
    private static final String ERROR_PLAN_PHASE_RUN_FAILED = "PLAN_PHASE_RUN_FAILED";
    /**
     * 生成最终报告失败时使用的错误码。
     */
    private static final String ERROR_PLAN_FINALIZE_FAILED = "PLAN_FINALIZE_FAILED";
    /**
     * 获取计划图谱失败时使用的错误码。
     */
    private static final String ERROR_PLAN_GRAPH_FAILED = "PLAN_GRAPH_FAILED";

    /**
     * 计划编排服务，负责执行计划主流程。
     */
    private final PlanOrchestratorService planOrchestratorService;
    /**
     * JSON 处理器，用于将字符串结果转为结构化响应。
     */
    private final ObjectMapper objectMapper;

    @PostMapping("/run")
    /**
     * 创建并执行一条新的计划。
     *
     * 该接口会校验用户目标、优先从登录上下文解析会话标识，并将编排结果统一包装后返回。
     */
    @Operation(summary = "创建并执行计划", description = "根据用户目标创建计划，并立即触发计划编排执行")
    public ResponseEntity<Object> run(@RequestBody PlanRunRequest req) {
        try {
            if (req == null || req.getUserGoal() == null || req.getUserGoal().isBlank()) {
                return badRequest("userGoal 不能为空");
            }

            String jwtJti = AuthContextHolder.getSessionId();
            String sessionId = (jwtJti != null && !jwtJti.isBlank())
                    ? jwtJti
                    : ((req.getSessionId() == null || req.getSessionId().isBlank()) ? SessionConstant.PLAN_DEFAULT_SESSION_ID : req.getSessionId().trim());

            log.info("plan run request, sessionId={}, userGoal={}", sessionId, req.getUserGoal());
            String result = planOrchestratorService.createAndRunPlan(sessionId, req.getUserGoal().trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("run failed", e);
            return serverError(ERROR_PLAN_RUN_FAILED, e.getMessage(), "计划执行失败");
        }
    }

    @PostMapping("/phase/run")
    /**
     * 执行指定计划中的单个阶段。
     *
     * 该接口要求明确传入计划标识和阶段标识，适用于分阶段推进计划时的手动触发场景。
     */
    @Operation(summary = "执行计划阶段", description = "根据计划 ID 和阶段 ID 执行指定的单个阶段")
    public ResponseEntity<Object> runPhase(@RequestBody PlanPhaseRunRequest req) {
        try {
            if (req == null || isBlank(req.getPlanId()) || isBlank(req.getPhaseId())) {
                return badRequest("planId 和 phaseId 不能为空");
            }

            log.info("phase run request, planId={}, phaseId={}", req.getPlanId(), req.getPhaseId());
            String result = planOrchestratorService.runPhase(req.getPlanId().trim(), req.getPhaseId().trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("runPhase failed", e);
            return serverError(ERROR_PLAN_PHASE_RUN_FAILED, e.getMessage(), "阶段执行失败");
        }
    }

    @PostMapping("/report/finalize")
    /**
     * 对已执行的计划做收尾处理并生成最终报告。
     */
    @Operation(summary = "生成计划报告", description = "对指定计划执行收尾逻辑并产出最终报告")
    public ResponseEntity<Object> finalizeReport(@RequestBody PlanFinalizeRequest req) {
        try {
            if (req == null || isBlank(req.getPlanId())) {
                return badRequest("planId 不能为空");
            }

            log.info("finalize request, planId={}", req.getPlanId());
            String result = planOrchestratorService.finalizeAndReport(req.getPlanId().trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("finalizeReport failed", e);
            return serverError(ERROR_PLAN_FINALIZE_FAILED, e.getMessage(), "计划收尾失败");
        }
    }

    @GetMapping("/graph/{planId}")
    /**
     * 查询指定计划的图结构快照，便于前端展示执行图谱。
     */
    @Operation(summary = "查询计划图谱", description = "根据计划 ID 获取当前计划的图结构快照")
    public ResponseEntity<Object> getPlanGraph(@PathVariable("planId") String planId) {
        try {
            if (isBlank(planId)) {
                return badRequest("planId 不能为空");
            }

            String result = planOrchestratorService.getPlanGraph(planId.trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("getPlanGraph failed", e);
            return serverError(ERROR_PLAN_GRAPH_FAILED, e.getMessage(), "获取计划图谱失败");
        }
    }

    /**
     * 统一构造参数校验失败响应，保持控制层错误结构一致。
     */
    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                KEY_ERROR_CODE, ERROR_INVALID_REQUEST,
                JsonFieldConstants.MESSAGE, message
        ));
    }

    /**
     * 统一构造服务端异常响应，便于前端按错误码区分处理。
     */
    private ResponseEntity<Object> serverError(String errorCode, String message, String defaultMessage) {
        return ResponseEntity.internalServerError().body(Map.of(
                JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                KEY_ERROR_CODE, errorCode,
                JsonFieldConstants.MESSAGE, message == null ? defaultMessage : message
        ));
    }

    /**
     * 优先将服务层返回值解析为 JSON；无法解析时退化为原始文本包装。
     */
    private Object parseOrRaw(String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node;
        } catch (Exception e) {
            return Map.of(
                    JsonFieldConstants.STATUS, ResultStatusConstants.SUCCESS,
                    JsonFieldConstants.RAW, text == null ? "" : text
            );
        }
    }

    /**
     * 判断字符串是否为空白，减少重复判空逻辑。
     */
    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
