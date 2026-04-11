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
/**
 * 内置 Bean 方法本地处理器，负责把本地工具调用路由到 Spring Bean 的公开方法执行。
 */
public class BuiltinBeanMethodLocalMcpToolHandler implements LocalMcpToolHandler {

    /**
     * Spring 容器，用于按名称获取目标 Bean。
     */
    private final ApplicationContext applicationContext;
    /**
     * JSON 处理器，用于解析参数和序列化执行结果。
     */
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
        /**
         * 先校验上下文是否支持当前处理器，再定位目标 Bean 和公开方法。
         */
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

            /**
             * 解析工具参数 JSON，并按方法签名构造调用参数后执行目标方法。
             */
            Map<String, Object> argsMap = parseArguments(context.argumentsJson());
            Object[] args = buildArguments(method, argsMap);
            Object result = method.invoke(bean, args);
            /**
             * 调用结束后统一规范化返回结构，兼容字符串、对象和空结果三类场景。
             */
            return normalizeResult(result);
        } catch (Exception e) {
            log.warn("invoke local tool handler failed, bean={}, method={}, toolName={}, err={}",
                    beanName, methodName, context.toolName(), e.getMessage());
            return error("TOOL_EXECUTION_FAILED", e.getMessage());
        }
    }

    /**
     * 在目标 Bean 中解析同名公开方法，优先选择参数更多的方法重载。
     */
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

    /**
     * 根据方法参数定义从参数映射中构建真实调用参数列表。
     */
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

    /**
     * 解析参数名，优先使用 RequestParam 显式声明，其次使用反射参数名。
     */
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

    /**
     * 将原始参数值转换为目标参数类型，必要时对基础类型做兜底处理。
     */
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

    /**
     * 解析参数 JSON 为键值映射，解析失败时返回空映射。
     */
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

    /**
     * 统一规范化方法执行结果，确保本地工具返回结构可被上游稳定消费。
     */
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

    /**
     * 构建本地处理器成功响应。
     */
    private String success(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "success");
        payload.put("data", data == null ? Map.of() : data);
        return toJson(payload);
    }

    /**
     * 构建本地处理器错误响应。
     */
    private String error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("errorCode", code);
        payload.put("message", message == null ? "" : message);
        return toJson(payload);
    }

    /**
     * 安全序列化对象为 JSON，失败时返回最小错误结构。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"errorCode\":\"TOOL_SERIALIZE_ERROR\"}";
        }
    }

    /**
     * 规范化文本输入，空值返回空字符串。
     */
    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
