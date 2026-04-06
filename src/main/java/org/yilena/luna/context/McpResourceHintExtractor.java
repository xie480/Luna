package org.yilena.luna.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.McpFieldConstants;
import org.yilena.luna.constants.McpProtocolConstants;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.enums.CapabilityTypeEnum;
import org.yilena.luna.service.McpService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpResourceHintExtractor {

    private final McpService mcpService;
    private final ObjectMapper objectMapper;

    private static final String PREFIX_PROMPT_HINT = "prompt_hint";
    private static final String PREFIX_RESOURCE_HINT = "resource_hint";
    private static final String PREFIX_WORKFLOW_HINT = "workflow_hint";
    private static final String PREFIX_TOOL_HINT = "tool_hint";
    private static final String PREFIX_GENERIC_HINT = "mcp_hint";

    public List<String> extract(List<Map<String, Object>> promptAndResourceCandidates, int limit) {
        if (promptAndResourceCandidates == null || promptAndResourceCandidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> promptCandidates = new ArrayList<>();
        List<Map<String, Object>> resourceCandidates = new ArrayList<>();
        List<Map<String, Object>> workflowCandidates = new ArrayList<>();
        List<Map<String, Object>> toolCandidates = new ArrayList<>();
        for (Map<String, Object> row : promptAndResourceCandidates) {
            CapabilityTypeEnum type = CapabilityTypeEnum.fromCode(safe(row == null ? null : row.get(McpFieldConstants.CAPABILITY_TYPE)));
            switch (type) {
                case PROMPT -> promptCandidates.add(row);
                case RESOURCE -> resourceCandidates.add(row);
                case WORKFLOW -> workflowCandidates.add(row);
                case TOOL -> toolCandidates.add(row);
                default -> {
                }
            }
        }
        return extract(promptCandidates, resourceCandidates, workflowCandidates, toolCandidates, limit);
    }

    public List<String> extract(List<Map<String, Object>> promptCandidates,
                                List<Map<String, Object>> resourceCandidates,
                                List<Map<String, Object>> workflowCandidates,
                                List<Map<String, Object>> toolCandidates,
                                int limit) {
        List<Map<String, Object>> mergedCandidates = mergeByOrder(promptCandidates, resourceCandidates, workflowCandidates, toolCandidates);
        if (mergedCandidates.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (Map<String, Object> row : mergedCandidates) {
            if (hints.size() >= safeLimit) {
                break;
            }
            CapabilityTypeEnum type = CapabilityTypeEnum.fromCode(safe(row.get(McpFieldConstants.CAPABILITY_TYPE)));
            String name = safe(row.get(McpFieldConstants.CAPABILITY_NAME));
            String title = safe(row.get(McpFieldConstants.TITLE));
            String description = safe(row.get(McpFieldConstants.DESCRIPTION));
            if (name.isBlank() && title.isBlank() && description.isBlank()) {
                continue;
            }
            String prefix = switch (type) {
                case PROMPT -> PREFIX_PROMPT_HINT;
                case RESOURCE -> PREFIX_RESOURCE_HINT;
                case WORKFLOW -> PREFIX_WORKFLOW_HINT;
                case TOOL -> PREFIX_TOOL_HINT;
                default -> PREFIX_GENERIC_HINT;
            };
            String summary = prefix + ": " + compact(name, title, description);
            String body = fetchBody(type, row);
            if (body.isBlank()) {
                hints.add(summary);
            } else {
                hints.add(summary + "\nbody: " + body);
            }
        }
        return new ArrayList<>(hints);
    }

    @SafeVarargs
    private final List<Map<String, Object>> mergeByOrder(List<Map<String, Object>>... channels) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (channels == null) {
            return out;
        }
        for (List<Map<String, Object>> channel : channels) {
            if (channel == null || channel.isEmpty()) {
                continue;
            }
            out.addAll(channel);
        }
        return out;
    }

    private String compact(String name, String title, String description) {
        String head = !title.isBlank() ? title : name;
        String tail = semanticShorten(description, 160);
        if (tail.isBlank()) {
            return head;
        }
        return head + " | " + tail;
    }

    private String fetchBody(CapabilityTypeEnum type, Map<String, Object> row) {
        if (type != CapabilityTypeEnum.PROMPT && type != CapabilityTypeEnum.RESOURCE) {
            return "";
        }
        String serverCode = resolveServerCode(row);
        String invocationName = resolveInvocationName(type, row);
        if (serverCode.isBlank() || invocationName.isBlank()) {
            return "";
        }
        try {
            if (type == CapabilityTypeEnum.PROMPT) {
                McpPromptResult promptResult = mcpService.getPrompt(serverCode, invocationName, McpProtocolConstants.DEFAULT_ARGUMENTS_JSON);
                return shorten(safe(promptResult == null ? null : promptResult.getPromptContent()), 600);
            }
            McpResourceResult resourceResult = mcpService.readResource(serverCode, invocationName);
            return shorten(safe(resourceResult == null ? null : resourceResult.getData()), 600);
        } catch (Exception e) {
            log.debug("fetch MCP body failed, type={}, serverCode={}, invocationName={}, err={}",
                    type, serverCode, invocationName, e.getMessage());
            return "";
        }
    }

    private String resolveServerCode(Map<String, Object> row) {
        String serverCode = safe(row.get(McpFieldConstants.SERVER_CODE));
        if (!serverCode.isBlank()) {
            return serverCode;
        }
        Map<String, Object> metadata = metadataOf(row);
        serverCode = safe(metadata.get(McpFieldConstants.SERVER_CODE));
        if (!serverCode.isBlank()) {
            return serverCode;
        }
        String capabilityName = safe(row.get(McpFieldConstants.CAPABILITY_NAME));
        int idx = capabilityName.indexOf(':');
        if (idx > 0) {
            return capabilityName.substring(0, idx);
        }
        return "";
    }

    private String resolveInvocationName(CapabilityTypeEnum type, Map<String, Object> row) {
        Map<String, Object> metadata = metadataOf(row);
        String fromMeta = safe(metadata.get(McpFieldConstants.INVOCATION_NAME));
        if (type == CapabilityTypeEnum.PROMPT && fromMeta.isBlank()) {
            fromMeta = safe(metadata.get(McpFieldConstants.PROMPT_NAME));
        }
        if (type == CapabilityTypeEnum.RESOURCE && fromMeta.isBlank()) {
            fromMeta = safe(metadata.get(McpFieldConstants.RESOURCE_URI));
        }
        if (!fromMeta.isBlank()) {
            return fromMeta;
        }
        String capabilityName = safe(row.get(McpFieldConstants.CAPABILITY_NAME));
        int idx = capabilityName.indexOf(':');
        if (idx > -1 && idx + 1 < capabilityName.length()) {
            return capabilityName.substring(idx + 1);
        }
        return capabilityName;
    }

    private Map<String, Object> metadataOf(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Object metadataRaw = row.get(McpFieldConstants.METADATA_JSON);
        if (metadataRaw instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            item -> String.valueOf(item.getKey()),
                            Map.Entry::getValue,
                            (left, right) -> right,
                            java.util.LinkedHashMap::new
                    ));
        }
        if (metadataRaw == null) {
            return Map.of();
        }
        String text = String.valueOf(metadataRaw).trim();
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String shorten(String text, int maxLen) {
        return semanticShorten(text, maxLen);
    }

    private String semanticShorten(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        List<String> segments = splitSemanticSegments(normalized);
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            String cleaned = safe(segment);
            if (cleaned.isBlank()) {
                continue;
            }
            int nextLength = out.length() + (out.length() == 0 ? 0 : 1) + cleaned.length();
            if (nextLength <= maxLen) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(cleaned);
                continue;
            }
            if (out.length() == 0) {
                out.append(trimToBoundary(cleaned, maxLen));
            }
            break;
        }
        String summarized = out.toString().trim();
        if (summarized.isBlank()) {
            summarized = trimToBoundary(normalized, maxLen);
        }
        if (summarized.length() < normalized.length()) {
            summarized = summarized + " ...";
        }
        return summarized.trim();
    }

    private List<String> splitSemanticSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = Pattern.compile("(?<=[。！？!?；;,.，])\\s+|\\n+").split(text);
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String cleaned = safe(part);
            if (!cleaned.isBlank()) {
                out.add(cleaned);
            }
        }
        if (out.isEmpty()) {
            return List.of(text);
        }
        return out;
    }

    private String trimToBoundary(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text.trim();
        }
        int safeLen = Math.max(12, maxLen);
        int boundary = -1;
        for (int i = Math.min(text.length() - 1, safeLen - 1); i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '。' || c == '，' || c == ',' || c == ';' || c == '；') {
                boundary = i;
                break;
            }
        }
        if (boundary < 0 || boundary < safeLen / 2) {
            boundary = safeLen;
        }
        return text.substring(0, Math.min(boundary, text.length())).trim();
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
