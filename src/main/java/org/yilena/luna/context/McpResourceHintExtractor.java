package org.yilena.luna.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class McpResourceHintExtractor {

    public List<String> extract(List<Map<String, Object>> promptAndResourceCandidates, int limit) {
        if (promptAndResourceCandidates == null || promptAndResourceCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (Map<String, Object> row : promptAndResourceCandidates) {
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
            hints.add(prefix + ": " + compact(name, title, description));
            if (hints.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return new ArrayList<>(hints);
    }

    private String compact(String name, String title, String description) {
        String head = !title.isBlank() ? title : name;
        String tail = description;
        if (tail.length() > 160) {
            tail = tail.substring(0, 160);
        }
        if (tail.isBlank()) {
            return head;
        }
        return head + " | " + tail;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
