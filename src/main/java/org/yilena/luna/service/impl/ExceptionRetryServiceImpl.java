package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.yilena.luna.exception.LunaExceptionContext;
import org.yilena.luna.service.ExceptionAgentService;
import org.yilena.luna.service.ExceptionRetryService;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionRetryServiceImpl**Assembling the Components**

I'm finishing the file generation. I've created all the necessary components: `LunaExceptionContext`, `ExceptionAgentService`, `ExceptionRetryService`, `ExceptionRetryServiceImpl`, and `GlobalExceptionHandler`. I've added a basic implementation for the `executeTool` method using reflection to call the Spring beans, which should satisfy the user's prompt, and all files are generated. I am confident that the code will work, and will now assemble it.


 implements ExceptionRetryService {

    private final ExceptionAgentService exceptionAgentService;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> handleException(LunaExceptionContext context) {
        Map<String, Object> result = new HashMap<>();
        String errorId = UUID.randomUUID().toString();
        result.put("errorId", errorId);
        result.put("success", false);

        // 防止无限循环：如果重试次数超过 1，直接返回兜底提示
        if (context.getRetryCount() > 1) {
            log.warn("异常重试次数超限，直接返回。ErrorID: {}", errorId);
            result.put("message", "唔...这个问题有点顽固，我尝试修复了几次都没有成功。建议你稍后再试，或者联系开发者查看日志。");
            result.put("reason", "重试次数超限");
            return result;
        }

        // 1. 调用 AI 分析
        JsonNode aiDecision = exceptionAgentService.analyzeException(context);
        if (aiDecision == null) {
            result.put("message", "系统发生未知错误，且 AI 辅助分析失败。");
            result.put("reason", "AI 服务不可用");
            return result;
        }

        boolean canFix = aiDecision.has("canFix") && aiDecision.get("canFix").asBoolean();

        // 2. 根据 AI 决策执行
        if (canFix) {
            String toolName = aiDecision.get("tool").asText();
            JsonNode params = aiDecision.get("params");
            log.info("AI 判定可修复，尝试调用工具: {}", toolName);

            try {
                // 3. 动态调用 MCP Tool
                Object toolResult = executeTool(toolName, params);
                
                // 4. 修复成功，返回提示
                result.put("success", true);
                result.put("message", "刚刚出了点小差错，不过我已经通过 " + toolName + " 自动修复啦！请重新尝试一下操作。");
                result.put("reason", "AI 自动修复成功");
                result.put("repairResult", toolResult);
            } catch (Exception e) {
                log.error("AI 尝试修复失败", e);
                // 修复失败，返回遗憾的提示
                result.put("message", "我尝试自动修复这个问题，但是执行工具时又失败了... (｡•́︿•̀｡)");
                result.put("reason", "自动修复工具执行失败: " + e.getMessage());
            }
        } else {
            // 5. 无法修复，返回人设化提示
            String message = aiDecision.has("message") ? aiDecision.get("message").asText() : "系统异常";
            String reason = aiDecision.has("reason") ? aiDecision.get("reason").asText() : "未知原因";
            result.put("message", message);
            result.put("reason", reason);
        }

        return result;
    }

    /**
     * 反射查找并执行 Tool 方法
     */
    private Object executeTool(String toolName, JsonNode params) throws Exception {
        // 扫描所有 Bean 查找 @Tool 方法
        // 注意：生产环境建议在启动时缓存 Tool Map，避免每次遍历
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            // 避免处理代理对象导致的问题，简单场景直接反射
            for (Method method : bean.getClass().getMethods()) {
                // 匹配 @Tool 注解且方法名一致
                if (method.isAnnotationPresent(Tool.class) && method.getName().equals(toolName)) {
                    return invokeMethod(bean, method, params);
                }
            }
        }
        throw new RuntimeException("未找到工具: " + toolName);
    }

    private Object invokeMethod(Object bean, Method method, JsonNode params) throws Exception {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String paramName = parameter.getName(); 
            // 尝试从 JSON 中获取参数
            if (params != null && params.has(paramName)) {
                JsonNode paramValue = params.get(paramName);
                // 使用 ObjectMapper 将 JSON 节点转换为参数对应的类型
                args[i] = objectMapper.treeToValue(paramValue, parameter.getType());
            } else {
                args[i] = null; // 参数缺失则传 null
            }
        }
        return method.invoke(bean, args);
    }
}
