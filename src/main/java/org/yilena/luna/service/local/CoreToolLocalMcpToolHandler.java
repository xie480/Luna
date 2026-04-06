package org.yilena.luna.service.local;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.BooleanTextConstants;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.LocalToolConstants;
import org.yilena.luna.constants.McpProtocolConstants;
import org.yilena.luna.constants.ResultStatusConstants;
import org.yilena.luna.tools.KnowledgeBaseTools;
import org.yilena.luna.tools.LogTools;
import org.yilena.luna.tools.MemoryTools;
import org.yilena.luna.tools.ScheduleTools;
import org.yilena.luna.tools.SearchTools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoreToolLocalMcpToolHandler implements LocalMcpToolHandler {

    private static final Set<String> CORE_TOOLS = Set.of(
            LocalToolConstants.TOOL_MANAGE_MEMORY,
            LocalToolConstants.TOOL_MANAGE_SCHEDULE_TASK,
            LocalToolConstants.TOOL_MANAGE_KNOWLEDGE_BASE,
            LocalToolConstants.TOOL_MANAGE_LOG,
            LocalToolConstants.TOOL_WEB_SEARCH,
            LocalToolConstants.TOOL_IMAGE_SEARCH,
            LocalToolConstants.TOOL_NEWS_SEARCH,
            LocalToolConstants.TOOL_LENS_SEARCH,
            LocalToolConstants.TOOL_WEB_SCRAPE
    );

    private final MemoryTools memoryTools;
    private final ScheduleTools scheduleTools;
    private final KnowledgeBaseTools knowledgeBaseTools;
    private final LogTools logTools;
    private final SearchTools searchTools;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() {
        return LocalToolConstants.TOOL_MANAGE_MEMORY;
    }

    @Override
    public List<String> aliases() {
        return CORE_TOOLS.stream().filter(name -> !LocalToolConstants.TOOL_MANAGE_MEMORY.equals(name)).toList();
    }

    @Override
    public boolean supports(InvocationContext context) {
        if (context == null) {
            return false;
        }
        if (!LocalToolConstants.IMPL_TYPE_LOCAL_HANDLER.equalsIgnoreCase(text(context.implType()))) {
            return false;
        }
        return CORE_TOOLS.contains(normalize(context.toolName()));
    }

    @Override
    public String handle(InvocationContext context) {
        try {
            String tool = normalize(context == null ? null : context.toolName());
            Map<String, Object> args = parseArgs(context == null ? McpProtocolConstants.DEFAULT_ARGUMENTS_JSON : context.argumentsJson());
            return switch (tool) {
                case LocalToolConstants.TOOL_MANAGE_MEMORY -> memoryTools.manageMemory(
                        stringArg(args, "action"),
                        longArg(args, "id"),
                        stringArg(args, "sessionId"),
                        stringArg(args, "memoryDomain"),
                        stringArg(args, "memoryLayer"),
                        stringArg(args, "factType"),
                        stringArg(args, "factKey"),
                        stringArg(args, "content"),
                        boolArg(args, "hardDelete")
                );
                case LocalToolConstants.TOOL_MANAGE_SCHEDULE_TASK -> scheduleTools.manageScheduleTask(
                        stringArg(args, "action"),
                        longArg(args, "id"),
                        stringArg(args, "mode"),
                        stringArg(args, "content"),
                        stringArg(args, "triggerTime"),
                        stringArg(args, "status"),
                        stringArg(args, "taskType"),
                        boolArg(args, "hardDelete")
                );
                case LocalToolConstants.TOOL_MANAGE_KNOWLEDGE_BASE -> knowledgeBaseTools.manageKnowledgeBase(
                        stringArg(args, "action"),
                        stringArg(args, "title"),
                        stringArg(args, "content"),
                        stringArg(args, "sourceType"),
                        stringArg(args, "sourcePath"),
                        stringArg(args, "query")
                );
                case LocalToolConstants.TOOL_MANAGE_LOG -> logTools.manageLog(
                        stringArg(args, "action"),
                        stringArg(args, "logType"),
                        stringArg(args, "module"),
                        stringArg(args, "content"),
                        stringArg(args, "startTime"),
                        stringArg(args, "endTime"),
                        intArg(args, "limit"),
                        longArg(args, "id"),
                        stringArg(args, "beforeTime")
                );
                case LocalToolConstants.TOOL_WEB_SEARCH -> searchTools.web_search(stringArg(args, "query"));
                case LocalToolConstants.TOOL_IMAGE_SEARCH -> searchTools.image_search(stringArg(args, "query"));
                case LocalToolConstants.TOOL_NEWS_SEARCH -> searchTools.news_search(stringArg(args, "query"));
                case LocalToolConstants.TOOL_LENS_SEARCH -> searchTools.lens_search(stringArg(args, "url"));
                case LocalToolConstants.TOOL_WEB_SCRAPE -> searchTools.web_scrape(stringArg(args, "url"));
                default -> error("TOOL_LOCAL_HANDLER_NOT_SUPPORTED", "Unsupported core local tool: " + tool);
            };
        } catch (Exception e) {
            log.warn("core local mcp tool execution failed, tool={}, err={}",
                    context == null ? "" : context.toolName(), e.getMessage());
            return error("TOOL_LOCAL_HANDLER_FAILED", e.getMessage());
        }
    }

    @Override
    public String handle(String argumentsJson) {
        return error("TOOL_CONTEXT_REQUIRED", "InvocationContext is required");
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private Integer intArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private Boolean boolArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (BooleanTextConstants.TRUE.equals(text)
                || BooleanTextConstants.ONE.equals(text)
                || BooleanTextConstants.YES.equals(text)) {
            return true;
        }
        if (BooleanTextConstants.FALSE.equals(text)
                || BooleanTextConstants.ZERO.equals(text)
                || BooleanTextConstants.NO.equals(text)) {
            return false;
        }
        return null;
    }

    private String error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(JsonFieldConstants.STATUS, ResultStatusConstants.ERROR);
        payload.put(JsonFieldConstants.ERROR_CODE, code);
        payload.put(JsonFieldConstants.MESSAGE, message == null ? "" : message);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return LocalToolConstants.STATUS_ERROR_JSON;
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
