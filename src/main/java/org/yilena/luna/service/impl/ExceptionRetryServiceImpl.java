package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.exception.LunaExceptionContext;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.service.ExceptionAgentService;
import org.yilena.luna.service.ExceptionRetryService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异常重试服务实现，负责根据异常分析结果决定是否调用工具自动修复，并向前端同步修复状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionRetryServiceImpl implements ExceptionRetryService {

    private final ExceptionAgentService exceptionAgentService;
    private final McpService mcpService;
    private final ToolExecutionGateway toolExecutionGateway;
    private final LunaStatusPublisher statusPublisher;

    @Override
    public Map<String, Object> handleException(LunaExceptionContext context) {
        Map<String, Object> result = new HashMap<>();
        String errorId = UUID.randomUUID().toString();
        result.put("errorId", errorId);
        result.put("success", false);

        /**
         * 先做重试次数兜底，避免异常分析和自动修复再次触发递归失败。
         */
        if (context.getRetryCount() > 1) {
            log.warn("异常重试次数超限，直接返回兜底结果，errorId={}", errorId);
            result.put("message", "这个问题暂时没有自动修复成功，建议稍后重试或查看日志。");
            result.put("reason", "重试次数超限");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return result;
        }

        /**
         * 进入异常分析阶段前先推送前端状态，让用户知道系统正在尝试定位原因。
         */
        statusPublisher.publish(
                LunaStatusPublisher.DEFAULT_CLIENT_ID,
                "ANALYZING",
                "遇到了一点小状况，Luna 正在分析原因..."
        );

        /**
         * 调用异常分析代理产出结构化决策，决定是否可以进入自动修复流程。
         */
        JsonNode aiDecision = exceptionAgentService.analyzeException(context);
        if (aiDecision == null) {
            result.put("message", "系统发生未知错误，且 AI 辅助分析失败。");
            result.put("reason", "AI 服务不可用");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return result;
        }

        boolean canFix = aiDecision.has("canFix") && aiDecision.get("canFix").asBoolean();

        /**
         * 如果分析结果允许修复，则根据模型返回的工具和参数发起自动修复。
         */
        if (canFix) {
            String toolName = aiDecision.get("tool").asText();
            JsonNode params = aiDecision.get("params");
            log.info("AI 判定可修复，尝试调用 MCP 工具: {}", toolName);
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    "FIXING",
                    "Luna 正在尝试调用工具进行自我修复..."
            );

            try {
                /**
                 * 先在资源目录中定位实际可执行的工具资源，避免模型返回了不存在的工具名。
                 */
                List<Resource> resources = mcpService.searchResources(toolName);
                Resource targetResource = resources.stream()
                        .filter(r -> ResourceType.TOOL.equals(r.getType()))
                        .filter(r -> r.getName().equals(toolName))
                        .findFirst()
                        .orElse(null);
                if (targetResource == null) {
                    throw new RuntimeException("MCP 资源中心未找到工具: " + toolName);
                }

                /**
                 * 为修复任务生成稳定会话标识，优先复用 JWT 会话，保证链路追踪可关联。
                 */
                String jwtJti = AuthContextHolder.getSessionId();
                String stableSessionId = (jwtJti != null && !jwtJti.isBlank())
                        ? jwtJti
                        : "exception-retry-" + errorId;

                /**
                 * 通过统一工具网关执行修复动作，并提取可回传给前端的执行结果。
                 */
                String safeParamsJson = (params == null || params.isNull()) ? "{}" : params.toString();
                ExecutionResult exec = toolExecutionGateway.executeTool(stableSessionId, targetResource, safeParamsJson);
                String toolResult = (exec.getRawResult() != null && !exec.getRawResult().isBlank())
                        ? exec.getRawResult()
                        : String.valueOf(exec.getData());

                /**
                 * 修复成功后返回用户可理解的结论，并附带工具执行结果供界面展示。
                 */
                result.put("success", true);
                result.put("message", "刚才出现了小问题，不过 Luna 已经通过 " + toolName + " 自动修复，请重新尝试操作。");
                result.put("reason", "AI 自动修复成功");
                result.put("repairResult", toolResult);
            } catch (NeedApprovalException e) {
                /**
                 * 如果修复动作触发审批，则将结果标记为待审批，让前端继续接管确认流程。
                 */
                log.info("自动修复触发审批流程，taskId={}", e.getApprovalTask().getTaskId());
                result.put("success", true);
                result.put("status", "pending_approval");
                result.put("message", "自动修复操作需要审批，请先在前端确认。");
                result.put("reason", "AUTO_FIX_NEED_APPROVAL");
                result.put("taskId", e.getApprovalTask().getTaskId());
            } catch (Exception e) {
                /**
                 * 如果工具执行仍然失败，则记录失败原因并退回人工处理提示。
                 */
                log.error("AI 尝试修复失败", e);
                result.put("message", "Luna 尝试自动修复这个问题，但执行工具时仍然失败。");
                result.put("reason", "自动修复工具执行失败: " + e.getMessage());
            }
        } else {
            /**
             * 对于无法修复的异常，直接透传模型生成的人类可读解释与原因说明。
             */
            String message = aiDecision.has("message") ? aiDecision.get("message").asText() : "系统异常";
            String reason = aiDecision.has("reason") ? aiDecision.get("reason").asText() : "未知原因";
            result.put("message", message);
            result.put("reason", reason);
        }

        /**
         * 无论结果如何，最后都恢复为空闲状态，避免前端长期停留在分析或修复中。
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
        return result;
    }
}
