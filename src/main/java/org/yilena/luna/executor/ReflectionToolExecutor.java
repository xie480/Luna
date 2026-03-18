package org.yilena.luna.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 基於反射的動態工具執行引擎
 * 替代 LangChain4j 的自動調用機制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionToolExecutor {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    /**
     * 執行工具
     *
     * @param resource 工具資源定義
     * @param argsJson LLM 生成的參數 JSON 字符串
     * @return 執行結果 JSON 字符串
     */
    public String execute(Resource resource, String argsJson) {
        try {
            log.info("正在執行工具: {} (Bean: {}, Method: {})", resource.getName(), resource.getBeanName(), resource.getMethodName());
            
            // 1. 從 Spring 容器獲取目標 Bean
            if (!applicationContext.containsBean(resource.getBeanName())) {
                return error("未找到 Bean: " + resource.getBeanName());
            }
            Object bean = applicationContext.getBean(resource.getBeanName());

            // 2. 獲取目標方法
            // 這裡簡化處理，假設方法名唯一。如果支持重載，需要更複雜的匹配邏輯。
            Method targetMethod = null;
            for (Method m : bean.getClass().getMethods()) {
                if (m.getName().equals(resource.getMethodName())) {
                    targetMethod = m;
                    break;
                }
            }
            
            if (targetMethod == null) {
                return error("未找到方法: " + resource.getMethodName());
            }

            // 3. 參數綁定：將 JSON 字符串解析為方法所需的 Object[]
            Object[] args = resolveArgs(targetMethod, argsJson);

            // 4. 反射執行
            Object result = targetMethod.invoke(bean, args);

            // 5. 處理返回結果
            if (result instanceof String) {
                return (String) result; // 工具本身返回的就是 JSON 字符串
            }
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("工具執行異常", e);
            return error("執行異常: " + e.getMessage());
        }
    }

    private Object[] resolveArgs(Method method, String argsJson) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(argsJson);
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String paramName = parameter.getName(); // 需確保編譯時開啟了 -parameters 參數，或者在 Resource 中存儲參數名列表
            
            // 嘗試從 JSON 中獲取參數
            // 支持兩種情況：
            // 1. JSON 是一個對象，key 對應參數名
            // 2. 只有一個參數且 JSON 是單個值（較少見，通常 LLM 輸出 Object）
            
            if (jsonNode.has(paramName)) {
                args[i] = objectMapper.treeToValue(jsonNode.get(paramName), parameter.getType());
            } else {
                // 如果 JSON 中沒有該參數，傳入 null
                args[i] = null;
                log.warn("參數 [{}] 在輸入 JSON 中缺失，將使用 null", paramName);
            }
        }
        return args;
    }

    private String error(String message) {
        return "{\"status\":\"error\", \"message\":\"" + message + "\"}";
    }
}
