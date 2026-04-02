package org.yilena.luna.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 基於反射的動態工具執行引擎
 * 替代 LangChain4j 的自動調用機制
 * 集成 ExecutionGate 功能
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionToolExecutor {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    /**
     * 內部執行邏輯 (繞過敏感度檢查)
     * 供 ApprovalService 在用戶批准後調用
     */
    public String executeInternal(String beanName, String methodName, String argsJson) {
        try {
            log.info("正在執行工具 (Internal): Bean={}, Method={}", beanName, methodName);
            
            // 1. 從 Spring 容器獲取目標 Bean
            if (!applicationContext.containsBean(beanName)) {
                return error("未找到 Bean: " + beanName);
            }
            Object bean = applicationContext.getBean(beanName);

            // 2. 獲取目標方法 (此時獲取到的可能是 AOP 代理類的方法)
            Method targetMethod = null;
            for (Method m : bean.getClass().getMethods()) {
                if (m.getName().equals(methodName)) {
                    targetMethod = m;
                    break;
                }
            }
            
            if (targetMethod == null) {
                return error("未找到方法: " + methodName);
            }

            // 3. 參數綁定：將 JSON 字符串解析為方法所需的 Object[]
            Object[] args = resolveArgs(bean, targetMethod, argsJson);

            // 4. 反射執行 (調用代理類的方法，確保 AOP 切面生效)
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

    private Object[] resolveArgs(Object bean, Method proxyMethod, String argsJson) throws Exception {
        log.info("解析參數: {}", argsJson);
        JsonNode jsonNode = objectMapper.readTree(argsJson);

        // 獲取真實的目標類 (解決 Spring AOP 代理類丟失參數註解的問題)
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        // 獲取真實類上的對應方法
        Method realMethod = ReflectionUtils.findMethod(targetClass, proxyMethod.getName(), proxyMethod.getParameterTypes());
        if (realMethod == null) {
            realMethod = proxyMethod;
        }

        Parameter[] parameters = realMethod.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String paramName = parameter.getName(); // 默認使用參數名 (arg0 if not compiled with -parameters)
            boolean required = false;

            // 優先讀取 @RequestParam 註解定義的名稱 (從真實類的方法參數上讀取)
            if (parameter.isAnnotationPresent(RequestParam.class)) {
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                if (requestParam.value() != null && !requestParam.value().isEmpty()) {
                    paramName = requestParam.value();
                } else if (requestParam.name() != null && !requestParam.name().isEmpty()) {
                    paramName = requestParam.name();
                }
                required = requestParam.required();
            }
            
            // 嘗試從 JSON 中獲取參數
            if (jsonNode.has(paramName) && !jsonNode.get(paramName).isNull()) {
                args[i] = objectMapper.treeToValue(jsonNode.get(paramName), proxyMethod.getParameterTypes()[i]);
            } else {
                // 如果 JSON 中沒有該參數，傳入 null
                args[i] = null;
                if (required) {
                    log.warn("必填參數 [{}] 在輸入 JSON 中缺失，將使用 null（可能導致工具執行失敗）", paramName);
                } else {
                    log.debug("可選參數 [{}] 在輸入 JSON 中缺失，將使用 null", paramName);
                }
            }
        }
        return args;
    }

    private String error(String message) {
        return "{\"status\":\"error\", \"message\":\"" + message + "\"}";
    }
}
