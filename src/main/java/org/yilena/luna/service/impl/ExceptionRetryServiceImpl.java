package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.exception.LunaExceptionContext;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.executor.ReflectionToolExecutor;
import org.yilena.luna.service.ExceptionAgentService;
import org.yilena.luna.service.ExceptionRetryService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionRetryServiceImpl implements ExceptionRetryService {

    private final ExceptionAgentService exceptionAgentService;
    private final McpService mcpService;
    private final ReflectionToolExecutor toolExecutor;
    private final LunaStatusPublisher statusPublisher;

    @Override
    public Map<String, Object> handleException(LunaExceptionContext context) {
        Map<String, Object> result = new HashMap<>();
        String errorId = UUID.randomUUID().toString();
        result.put("errorId", errorId);
        result.put("success", false);

        // 防止无限循环：如果重试次数超过 1，直接返回兜底提示
        if (context.getRetryCount() > 1) {
            log.warn("异常重试次数超限，直接返回。ErrorID: {}", errorId);
            result.put("message", "唔...这个问题有点顽固，Luna尝试修复了几次都没有成功。建议主人稍后再试，或者查看日志。");
            result.put("reason", "重试次数超限");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return result;
        }

        // 推送状态：正在分析异常
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "ANALYZING", "哎呀，遇到点小状况，Luna 正在分析原因...");

        // 1. 调用 AI 分析
        JsonNode aiDecision = exceptionAgentService.analyzeException(context);
        if (aiDecision == null) {
            result.put("message", "系统发生未知错误，且 AI 辅助分析失败。");
            result.put("reason", "AI 服务不可用");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return result;
        }

        boolean canFix = aiDecision.has("canFix") && aiDecision.get("canFix").asBoolean();

        // 2. 根据 AI 决策执行
        if (canFix) {
            String toolName = aiDecision.get("tool").asText();
            JsonNode params = aiDecision.get("params");
            log.info("AI 判定可修复，尝试调用 MCP 工具: {}", toolName);

            // 推送状态：正在尝试修复
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "FIXING", "Luna 正在尝试调用工具自我修复...");

            try {
                // 3. 动态调用 MCP Tool (替代原有的 @Tool 掃描)
                List<Resource> resources = mcpService.searchResources(toolName);
                Resource targetResource = resources.stream()
                        .filter(r -> r.getName().equals(toolName))
                        .findFirst()
                        .orElse(null);

                if (targetResource == null) {
                    throw new RuntimeException("MCP 註冊中心未找到工具: " + toolName);
                }

                // 4. 使用稳定 sessionId（优先 JWT jti）
                String jwtJti = AuthContextHolder.getSessionId();
                String stableSessionId = (jwtJti != null && !jwtJti.isBlank())
                        ? jwtJti
                        : "exception-retry-" + errorId;

                String toolResult = toolExecutor.execute(stableSessionId, targetResource, params.toString());

                // 5. 修复成功，返回提示
                result.put("success", true);
                result.put("message", "刚刚出了点小差错，不过Luna已经通过 " + toolName + " 自动修复啦！请重新尝试一下操作。");
                result.put("reason", "AI 自动修复成功");
                result.put("repairResult", toolResult);
            } catch (NeedApprovalException e) {
                // 6. 需要审批属于正常业务分支，不视为修复失败
                log.info("自动修复触发审批流程，taskId={}", e.getApprovalTask().getTaskId());
                result.put("success", true);
                result.put("status", "pending_approval");
                result.put("message", "自动修复操作需要审批，请先在前端确认。");
                result.put("reason", "AUTO_FIX_NEED_APPROVAL");
                result.put("taskId", e.getApprovalTask().getTaskId());
            } catch (Exception e) {
                log.error("AI 尝试修复失败", e);
                // 修复失败，返回遗憾的提示
                result.put("message", "Luna尝试自动修复这个问题，但是执行工具时又失败了... (｡•́︿•̀｡)");
                result.put("reason", "自动修复工具执行失败: " + e.getMessage());
            }
        } else {
            // 7. 无法修复，返回人设化提示
            String message = aiDecision.has("message") ? aiDecision.get("message").asText() : "系统异常";
            String reason = aiDecision.has("reason") ? aiDecision.get("reason").asText() : "未知原因";
            result.put("message", message);
            result.put("reason", reason);
        }

        // 恢复空闲状态
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
        return result;
    }
}
