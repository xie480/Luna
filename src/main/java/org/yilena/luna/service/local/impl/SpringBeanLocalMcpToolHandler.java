package org.yilena.luna.service.local.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.service.local.LocalMcpToolHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpringBeanLocalMcpToolHandler implements LocalMcpToolHandler {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() {
        return "__spring_bean_dispatcher__";
    }

    @Override
    public boolean supports(InvocationContext context) {
        if (context == null) {
            return false;
        }
        if ("LOCAL_HANDLER".equalsIgnoreCase(context.implType())) {
            return true;
        }
        return "SPRING_BEAN".equalsIgnoreCase(context.implType());
    }

    @Override
    public String handle(String argumentsJson) {
        return error("LOCAL_HANDLER_CONTEXT_REQUIRED");
    }

    @Override
    public String handle(InvocationContext context) {
        String beanName = context == null ? "" : context.beanName();
        String methodName = context == null ? "" : context.methodName();
        String argsJson = context == null ? "{}" : context.argumentsJson();
        if (beanName == null || beanName.isBlank() || methodName == null || methodName.isBlank()) {
            return error("LOCAL_HANDLER_TARGET_REQUIRED");
        }
        try {
            if (!applicationContext.containsBean(beanName)) {
                return error("LOCAL_HANDLER_BEAN_NOT_FOUND");
            }
            Object bean = applicationContext.getBean(beanName);
            Method targetMethod = findMethod(bean, methodName);
            if (targetMethod == null) {
                return error("LOCAL_HANDLER_METHOD_NOT_FOUND");
            }
            Object[] args = resolveArgs(bean, targetMethod, argsJson);
            Object result = targetMethod.invoke(bean, args);
            if (result instanceof String text) {
                return text;
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            log.warn("local spring handler invocation failed, tool={}, bean={}, method={}, msg={}",
                    context == null ? "" : context.toolName(),
                    beanName,
                    methodName,
                    ex.getMessage());
            return error("LOCAL_HANDLER_EXECUTE_FAILED");
        }
    }

    private Method findMethod(Object bean, String methodName) {
        for (Method method : bean.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    private Object[] resolveArgs(Object bean, Method proxyMethod, String argsJson) throws Exception {
        String rawArgs = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
        JsonNode jsonNode = objectMapper.readTree(rawArgs);
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Method realMethod = ReflectionUtils.findMethod(targetClass, proxyMethod.getName(), proxyMethod.getParameterTypes());
        if (realMethod == null) {
            realMethod = proxyMethod;
        }
        Parameter[] parameters = realMethod.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String paramName = parameter.getName();
            if (parameter.isAnnotationPresent(RequestParam.class)) {
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                if (requestParam.value() != null && !requestParam.value().isEmpty()) {
                    paramName = requestParam.value();
                } else if (requestParam.name() != null && !requestParam.name().isEmpty()) {
                    paramName = requestParam.name();
                }
            }
            JsonNode valueNode = findValueNode(jsonNode, paramName, i);
            if (valueNode == null || valueNode.isNull()) {
                args[i] = null;
                continue;
            }
            args[i] = objectMapper.treeToValue(valueNode, proxyMethod.getParameterTypes()[i]);
        }
        return args;
    }

    private JsonNode findValueNode(JsonNode jsonNode, String paramName, int index) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        if (paramName != null && !paramName.isBlank()) {
            if (jsonNode.has(paramName)) {
                return jsonNode.get(paramName);
            }
            String normalized = paramName.toLowerCase(Locale.ROOT);
            if (jsonNode.has(normalized)) {
                return jsonNode.get(normalized);
            }
        }
        String fallback = "arg" + index;
        if (jsonNode.has(fallback)) {
            return jsonNode.get(fallback);
        }
        return null;
    }

    private String error(String code) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "status", "error",
                    "errorCode", code
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\"}";
        }
    }
}
