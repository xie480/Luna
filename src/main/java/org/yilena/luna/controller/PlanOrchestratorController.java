package org.yilena.luna.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.query.PlanFinalizeRequest;
import org.yilena.luna.entity.query.PlanPhaseRunRequest;
import org.yilena.luna.entity.query.PlanRunRequest;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.Map;

/**
 * OpenClaw 计划编排控制器（MVP）
 * 对外暴露统一入口，便于前端和测试联调。
 */
@Slf4j
@RestController
@RequestMapping("/luna/api/plan")
@RequiredArgsConstructor
@Tag(name = "OpenClaw计划编排接口")
public class PlanOrchestratorController {

    private final PlanOrchestratorService planOrchestratorService;
    private final ObjectMapper objectMapper;

    @PostMapping("/run")
    @Operation(summary = "创建并执行计划（MVP）")
    public ResponseEntity<Object> run(@RequestBody PlanRunRequest req) {
        try {
            if (req == null || req.getUserGoal() == null || req.getUserGoal().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "errorCode", "INVALID_REQUEST",
                        "message", "userGoal 不能为空"
                ));
            }

            // 优先使用 JWT jti 作为稳定 sessionId
            String jwtJti = AuthContextHolder.getSessionId();
            String sessionId = (jwtJti != null && !jwtJti.isBlank())
                    ? jwtJti
                    : ((req.getSessionId() == null || req.getSessionId().isBlank()) ? "plan-default-session" : req.getSessionId().trim());

            log.info("收到计划执行请求, sessionId={}, userGoal={}", sessionId, req.getUserGoal());
            String result = planOrchestratorService.createAndRunPlan(sessionId, req.getUserGoal().trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("run 失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "errorCode", "PLAN_RUN_FAILED",
                    "message", e.getMessage() == null ? "计划执行失败" : e.getMessage()
            ));
        }
    }

    @PostMapping("/phase/run")
    @Operation(summary = "执行单阶段")
    public ResponseEntity<Object> runPhase(@RequestBody PlanPhaseRunRequest req) {
        try {
            if (req == null || isBlank(req.getPlanId()) || isBlank(req.getPhaseId())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "errorCode", "INVALID_REQUEST",
                        "message", "planId 和 phaseId 不能为空"
                ));
            }

            log.info("收到阶段执行请求, planId={}, phaseId={}", req.getPlanId(), req.getPhaseId());
            String result = planOrchestratorService.runPhase(req.getPlanId().trim(), req.getPhaseId().trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("runPhase 失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "errorCode", "PLAN_PHASE_RUN_FAILED",
                    "message", e.getMessage() == null ? "阶段执行失败" : e.getMessage()
            ));
        }
    }

    @PostMapping("/report/finalize")
    @Operation(summary = "收尾并生成报告")
    public ResponseEntity<Object> finalizeReport(@RequestBody PlanFinalizeRequest req) {
        try {
            if (req == null || isBlank(req.getPlanId())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "errorCode", "INVALID_REQUEST",
                        "message", "planId 不能为空"
                ));
            }

            log.info("收到计划收尾请求, planId={}", req.getPlanId());
            String result = planOrchestratorService.finalizeAndReport(req.getPlanId().trim());
            return ResponseEntity.ok(parseOrRaw(result));
        } catch (Exception e) {
            log.error("finalizeReport 失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "errorCode", "PLAN_FINALIZE_FAILED",
                    "message", e.getMessage() == null ? "计划收尾失败" : e.getMessage()
            ));
        }
    }

    private Object parseOrRaw(String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node;
        } catch (Exception e) {
            return Map.of("status", "success", "raw", text == null ? "" : text);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
