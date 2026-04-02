package org.yilena.luna.service.local;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Local MCP tool handler abstraction.
 * Each handler owns one MCP tool execution path without host-side reflection.
 */
public interface LocalMcpToolHandler {

    String toolName();

    default List<String> aliases() {
        return List.of();
    }

    String handle(String argumentsJson);

    default String handle(InvocationContext context) {
        String args = context == null ? "{}" : context.argumentsJson();
        return handle(args == null || args.isBlank() ? "{}" : args);
    }

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

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    record InvocationContext(
            String serverCode,
            String toolName,
            String implType,
            String beanName,
            String methodName,
            String argumentsJson
    ) {
        public InvocationContext {
            serverCode = Objects.toString(serverCode, "");
            toolName = Objects.toString(toolName, "");
            implType = Objects.toString(implType, "");
            beanName = Objects.toString(beanName, "");
            methodName = Objects.toString(methodName, "");
            argumentsJson = Objects.toString(argumentsJson, "{}");
        }
    }
}
