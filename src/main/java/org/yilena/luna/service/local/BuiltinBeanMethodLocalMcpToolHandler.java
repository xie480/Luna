package org.yilena.luna.service.local;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinBeanMethodLocalMcpToolHandler implements LocalMcpToolHandler {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() {
        return "__builtin.bean-method-bridge__";
    }

    @Override
    public String handle(String argumentsJson) {
        return error("TOOL_CONTEXT_REQUIRED", "InvocationContext is required");
    }

    @Override
    public boolean supports(InvocationContext context) {
        if (context == null) {
            return false;
        }
        if (!"SPRING_BEAN".equalsIgnoreCase(text(context.implType()))) {
            return false;
        }
        return !text(context.beanName()).isBlank() && !text(context.methodName()).isBlank();
    }

    @Override
    public String handle(InvocationContext context) {
        if (!supports(context)) {
            return error("TOOL_UNSUPPORTED_IMPL", "Unsupported invocation context");
        }
        String beanName = text(context.beanName());
        String methodName = text(context.methodName());
        try {
            Object bean = applicationContext.getBean(beanName);
            Method method = resolveMethod(bean.getClass(), methodName);
            if (method == null) {
                return error("TOOL_METHOD_NOT_FOUND",
                        "No public method found, bean=" + beanName + ", method=" + methodName);
            }

            Map<String, Object> argsMap = parseArguments(context.argumentsJson());
            Object[] args = buildArguments(method, argsMap);
            Object result = method.invoke(bean, args);
            return normalizeResult(result);
        } catch (Exception e) {
            log.warn("invoke local tool handler failed, bean={}, method={}, toolName={}, err={}",
                    beanName, methodName, context.toolName(), e.getMessage());
            return error("TOOL_EXECUTION_FAILED", e.getMessage());
        }
    }

    private Method resolveMethod(Class<?> beanClass, String methodName) {
        if (beanClass == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        List<Method> candidates = List.of(beanClass.getMethods()).stream()
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> methodName.equals(m.getName()))
                .sorted(Comparator.comparingInt((Method m) -> m.getParameterCount()).reversed())
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(0);
    }

    private Object[] buildArguments(Method method, Map<String, Object> argsMap) {
        Parameter[] parameters = method.getParameters();
        if (parameters == null || parameters.length == 0) {
            return new Object[0];
        }
        Object[] out = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String key = resolveParamName(parameter, i);
            Object raw = argsMap.get(key);
            if (raw == null && argsMap.size() == 1 && !argsMap.containsKey(key)) {
                raw = argsMap.values().iterator().next();
            }
            out[i] = convertArgument(raw, parameter.getType());
        }
        return out;
    }

    private String resolveParamName(Parameter parameter, int index) {
        if (parameter == null) {
            return "arg" + index;
        }
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            if (requestParam.value() != null && !requestParam.value().isBlank()) {
                return requestParam.value().trim();
            }
            if (requestParam.name() != null && !requestParam.name().isBlank()) {
                return requestParam.name().trim();
            }
        }
        if (parameter.isNamePresent() && parameter.getName() != null && !parameter.getName().isBlank()) {
            return parameter.getName();
        }
        return "arg" + index;
    }

    private Object convertArgument(Object raw, Class<?> targetType) {
        if (targetType == null) {
            return raw;
        }
        if (raw == null) {
            if (targetType.isPrimitive()) {
                if (boolean.class.equals(targetType)) {
                    return false;
                }
                if (char.class.equals(targetType)) {
                    return '\0';
                }
                return 0;
            }
            return null;
        }
        if (String.class.equals(targetType)) {
            if (raw instanceof Map || raw instanceof List) {
                try {
                    return objectMapper.writeValueAsString(raw);
                } catch (Exception ignore) {
                    return String.valueOf(raw);
                }
            }
            return String.valueOf(raw);
        }
        try {
            return objectMapper.convertValue(raw, targetType);
        } catch (Exception ignore) {
            try {
                return objectMapper.readValue(String.valueOf(raw), targetType);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<>() {
            });
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    private String normalizeResult(Object result) {
        if (result == null) {
            return success(Map.of());
        }
        if (result instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return text;
            }
            return success(Map.of("result", text));
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return success(Map.of("result", String.valueOf(result)));
        }
    }

    private String success(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "success");
        payload.put("data", data == null ? Map.of() : data);
        return toJson(payload);
    }

    private String error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("errorCode", code);
        payload.put("message", message == null ? "" : message);
        return toJson(payload);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"errorCode\":\"TOOL_SERIALIZE_ERROR\"}";
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
