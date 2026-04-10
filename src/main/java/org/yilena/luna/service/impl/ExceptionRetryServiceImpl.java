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
 * 异常重试服务实现，负责根据异常分析结果决定是否调用工具自动修复，
 * 并向前端同步修复状态。
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
         * 先做重试次数兜底，
         * 避免异常分析和自动修复再次触发递归失败。
         */
        if (context.getRetryCount() > 1) {
            log.warn("寮傚父閲嶈瘯娆℃暟瓒呴檺锛岀洿鎺ヨ繑鍥炲厹搴曠粨鏋滐紝errorId={}", errorId);
            result.put("message", "杩欎釜闂鏆傛椂娌℃湁鑷姩淇鎴愬姛锛屽缓璁◢鍚庨噸璇曟垨鏌ョ湅鏃ュ織銆?");
            result.put("reason", "閲嶈瘯娆℃暟瓒呴檺");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return result;
        }

        /**
         * 进入异常分析阶段前先推送前端状态，
         * 让用户知道系统正在尝试定位原因。
         */
        statusPublisher.publish(
                LunaStatusPublisher.DEFAULT_CLIENT_ID,
                "ANALYZING",
                "閬囧埌浜嗕竴鐐瑰皬鐘跺喌锛孡una 姝ｅ湪鍒嗘瀽鍘熷洜..."
        );

        /**
         * 调用异常分析代理输出结构化决策，
         * 决定是否可以进入自动修复流程。
         */
        JsonNode aiDecision = exceptionAgentService.analyzeException(context);
        if (aiDecision == null) {
            result.put("message", "绯荤粺鍙戠敓鏈煡閿欒锛屼笖 AI 杈呭姪鍒嗘瀽澶辫触銆?");
            result.put("reason", "AI 鏈嶅姟涓嶅彲鐢?");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return result;
        }

        boolean canFix = aiDecision.has("canFix") && aiDecision.get("canFix").asBoolean();

        /**
         * 如果分析结果允许修复，
         * 则根据模型返回的工具和参数发起自动修复。
         */
        if (canFix) {
            String toolName = aiDecision.get("tool").asText();
            JsonNode params = aiDecision.get("params");
            log.info("AI 鍒ゅ畾鍙慨澶嶏紝灏濊瘯璋冪敤 MCP 宸ュ叿: {}", toolName);
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    "FIXING",
                    "Luna 姝ｅ湪灏濊瘯璋冪敤宸ュ叿杩涜鑷垜淇..."
            );

            try {
                /**
                 * 先在资源目录中定位真实可执行的工具资源，
                 * 避免模型返回不存在的工具名。
                 */
                List<Resource> resources = mcpService.searchResources(toolName);
                Resource targetResource = resources.stream()
                        .filter(r -> ResourceType.TOOL.equals(r.getType()))
                        .filter(r -> r.getName().equals(toolName))
                        .findFirst()
                        .orElse(null);
                if (targetResource == null) {
                    throw new RuntimeException("MCP 璧勬簮涓績鏈壘鍒板伐鍏? " + toolName);
                }

                /**
                 * 为修复任务生成稳定会话标识，
                 * 优先复用 JWT 会话以保证链路可追踪。
                 */
                String jwtJti = AuthContextHolder.getSessionId();
                String stableSessionId = (jwtJti != null && !jwtJti.isBlank())
                        ? jwtJti
                        : "exception-retry-" + errorId;

                /**
                 * 通过统一工具网关执行修复动作，
                 * 并提取可回传给前端的执行结果。
                 */
                String safeParamsJson = (params == null || params.isNull()) ? "{}" : params.toString();
                ExecutionResult exec = toolExecutionGateway.executeTool(stableSessionId, targetResource, safeParamsJson);
                String toolResult = (exec.getRawResult() != null && !exec.getRawResult().isBlank())
                        ? exec.getRawResult()
                        : String.valueOf(exec.getData());

                /**
                 * 修复成功后返回用户可理解的结果说明，
                 * 并附带工具执行结果供界面展示。
                 */
                result.put("success", true);
                result.put("message", "鍒氭墠鍑虹幇浜嗗皬闂锛屼笉杩?Luna 宸茬粡閫氳繃 " + toolName + " 鑷姩淇锛岃閲嶆柊灏濊瘯鎿嶄綔銆?");
                result.put("reason", "AI 鑷姩淇鎴愬姛");
                result.put("repairResult", toolResult);
            } catch (NeedApprovalException e) {
                /**
                 * 如果修复动作触发审批，
                 * 则将结果标记为待审批并交由前端继续确认流程。
                 */
                log.info("鑷姩淇瑙﹀彂瀹℃壒娴佺▼锛宼askId={}", e.getApprovalTask().getTaskId());
                result.put("success", true);
                result.put("status", "pending_approval");
                result.put("message", "鑷姩淇鎿嶄綔闇€瑕佸鎵癸紝璇峰厛鍦ㄥ墠绔‘璁ゃ€?");
                result.put("reason", "AUTO_FIX_NEED_APPROVAL");
                result.put("taskId", e.getApprovalTask().getTaskId());
            } catch (Exception e) {
                /**
                 * 如果工具执行仍然失败，
                 * 则记录失败原因并回退到人工处理提示。
                 */
                log.error("AI 灏濊瘯淇澶辫触", e);
                result.put("message", "Luna 灏濊瘯鑷姩淇杩欎釜闂锛屼絾鎵ц宸ュ叿鏃朵粛鐒跺け璐ャ€?");
                result.put("reason", "鑷姩淇宸ュ叿鎵ц澶辫触: " + e.getMessage());
            }
        } else {
            /**
             * 对于无法修复的异常，
             * 直接透传模型生成的说明和原因。
             */
            String message = aiDecision.has("message") ? aiDecision.get("message").asText() : "绯荤粺寮傚父";
            String reason = aiDecision.has("reason") ? aiDecision.get("reason").asText() : "鏈煡鍘熷洜";
            result.put("message", message);
            result.put("reason", reason);
        }

        /**
         * 无论结果如何，最后都恢复为空闲状态，
         * 避免前端长期停留在分析或修复中。
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
        return result;
    }
}
