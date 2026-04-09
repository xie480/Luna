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
 * OpenClaw plan orchestration controller.
 */
@Slf4j
@RestController
@RequestMapping("/luna/api/plan")
@RequiredArgsConstructor
@Tag(name = "OpenClaw计划编排接口")
public class PlanOrchestratorController {

    private static final String KEY_ERROR_CODE = "errorCode";

    private static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERROR_PLAN_RUN_FAILED = "PLAN_RUN_FAILED";
    private static final String ERROR_PLAN_PHASE_RUN_FAILED = "PLAN_PHASE_RUN_FAILED";
    private static final String ERROR_PLAN_FINALIZE_FAILED = "PLAN_FINALIZE_FAILED";
    private static final String ERROR_PLAN_GRAPH_FAILED = "PLAN_GRAPH_FAILED";

    private final PlanOrchestratorService planOrchestratorService;
    private final ObjectMapper objectMapper;

    @PostMapping("/run")
    @Operation(summary = "创建并执行计划（MVP）")
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
    @Operation(summary = "执行单阶段")
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
    @Operation(summary = "收尾并生成报告")
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
    @Operation(summary = "获取计划图快照")
    public ResponseEntity<Object> getPlanGraph(@PathVariable("planId") String planId) {
        try {
            if (isBlank(planId)) {
                return badRequest("planId 不能为空");
            }

            String result = planOrchestratorService.getPlanGraph(planId.trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("getPlanGraph failed", e);
            return serverError(ERROR_PLAN_GRAPH_FAILED, e.getMessage(), "获取计划图失败");
        }
    }

    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                KEY_ERROR_CODE, ERROR_INVALID_REQUEST,
                JsonFieldConstants.MESSAGE, message
        ));
    }

    private ResponseEntity<Object> serverError(String errorCode, String message, String defaultMessage) {
        return ResponseEntity.internalServerError().body(Map.of(
                JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                KEY_ERROR_CODE, errorCode,
                JsonFieldConstants.MESSAGE, message == null ? defaultMessage : message
        ));
    }

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

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
