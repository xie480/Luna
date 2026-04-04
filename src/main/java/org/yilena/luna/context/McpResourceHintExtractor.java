package org.yilena.luna.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceResult;
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

    public List<String> extract(List<Map<String, Object>> promptAndResourceCandidates, int limit) {
        if (promptAndResourceCandidates == null || promptAndResourceCandidates.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (Map<String, Object> row : promptAndResourceCandidates) {
            if (hints.size() >= safeLimit) {
                break;
            }
            String type = safe(row.get("capability_type")).toUpperCase(Locale.ROOT);
            String name = safe(row.get("capability_name"));
            String title = safe(row.get("title"));
            String description = safe(row.get("description"));
            if (name.isBlank() && title.isBlank() && description.isBlank()) {
                continue;
            }
            String prefix = switch (type) {
                case "PROMPT" -> "prompt_hint";
                case "RESOURCE" -> "resource_hint";
                case "WORKFLOW" -> "workflow_hint";
                default -> "mcp_hint";
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

    private String compact(String name, String title, String description) {
        String head = !title.isBlank() ? title : name;
        String tail = semanticShorten(description, 160);
        if (tail.isBlank()) {
            return head;
        }
        return head + " | " + tail;
    }

    private String fetchBody(String type, Map<String, Object> row) {
        if (!"PROMPT".equals(type) && !"RESOURCE".equals(type)) {
            return "";
        }
        String serverCode = resolveServerCode(row);
        String invocationName = resolveInvocationName(type, row);
        if (serverCode.isBlank() || invocationName.isBlank()) {
            return "";
        }
        try {
            if ("PROMPT".equals(type)) {
                McpPromptResult promptResult = mcpService.getPrompt(serverCode, invocationName, "{}");
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
        String serverCode = safe(row.get("server_code"));
        if (!serverCode.isBlank()) {
            return serverCode;
        }
        Map<String, Object> metadata = metadataOf(row);
        serverCode = safe(metadata.get("server_code"));
        if (!serverCode.isBlank()) {
            return serverCode;
        }
        String capabilityName = safe(row.get("capability_name"));
        int idx = capabilityName.indexOf(':');
        if (idx > 0) {
            return capabilityName.substring(0, idx);
        }
        return "";
    }

    private String resolveInvocationName(String type, Map<String, Object> row) {
        Map<String, Object> metadata = metadataOf(row);
        String fromMeta = safe(metadata.get("invocation_name"));
        if ("PROMPT".equals(type) && fromMeta.isBlank()) {
            fromMeta = safe(metadata.get("prompt_name"));
        }
        if ("RESOURCE".equals(type) && fromMeta.isBlank()) {
            fromMeta = safe(metadata.get("resource_uri"));
        }
        if (!fromMeta.isBlank()) {
            return fromMeta;
        }
        String capabilityName = safe(row.get("capability_name"));
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
        Object metadataRaw = row.get("metadata_json");
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
