package org.yilena.luna.service.local;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 本地 MCP 工具处理器接口，负责抽象本地工具的匹配、参数处理和执行入口。
 */
public interface LocalMcpToolHandler {

    /**
     * 返回处理器主工具名。
     */
    String toolName();

    /**
     * 返回处理器支持的别名列表，默认无别名。
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 按原始参数 JSON 执行工具。
     */
    String handle(String argumentsJson);

    /**
     * 返回工具输入 Schema，默认为空。
     */
    default Map<String, Object> inputSchema() {
        return Map.of();
    }

    /**
     * 返回工具输出 Schema，默认为空。
     */
    default Map<String, Object> outputSchema() {
        return Map.of();
    }

    /**
     * 返回处理器默认超时时间，单位为毫秒。
     */
    default int timeoutMs() {
        return 10000;
    }

    /**
     * 返回当前处理器使用的错误码前缀。
     */
    default String errorCodePrefix() {
        return "TOOL";
    }

    /**
     * 按结构化上下文执行工具，默认回退到原始参数 JSON 执行。
     */
    default String handle(InvocationContext context) {
        String args = context == null ? "{}" : context.argumentsJson();
        return handle(args == null || args.isBlank() ? "{}" : args);
    }

    /**
     * 判断当前处理器是否支持给定调用上下文。
     */
    default boolean supports(InvocationContext context) {
        if (context == null) {
            return false;
        }
        String current = normalize(toolName());
        if (!current.isBlank() && current.equals(normalize(context.toolName()))) {
            return true;
        }
        for (String alias : aliases()) {
            if (normalize(alias).equals(normalize(context.toolName()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规范化工具名，统一转为小写便于比较。
     */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 本地工具调用上下文，负责承载服务端编码、工具名、实现类型和参数等元信息。
     */
    record InvocationContext(
            String serverCode,
            String toolName,
            String implType,
            String beanName,
            String methodName,
            String argumentsJson
    ) {
        public InvocationContext {
            /**
             * 构造时统一将可空字段规范化，避免后续处理出现空指针。
             */
            serverCode = Objects.toString(serverCode, "");
            toolName = Objects.toString(toolName, "");
            implType = Objects.toString(implType, "");
            beanName = Objects.toString(beanName, "");
            methodName = Objects.toString(methodName, "");
            argumentsJson = Objects.toString(argumentsJson, "{}");
        }
    }
}
