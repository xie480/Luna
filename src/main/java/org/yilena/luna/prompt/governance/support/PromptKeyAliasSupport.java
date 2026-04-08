package org.yilena.luna.prompt.governance.support;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PromptKeyAliasSupport {

    private static final Map<String, String> LEGACY_TO_CANONICAL = Map.ofEntries(
            Map.entry("repair.main_json_v1", "repair.main.json_v1"),
            Map.entry("agent.reconstruction.default_v1", "agent-local.reconstruction.default_v1"),
            Map.entry("agent.rerank.default_v1", "agent-local.rerank.default_v1"),
            Map.entry("agent.recovery.default_v1", "agent-local.recovery.default_v1"),
            Map.entry("agent.tool_semantic.default_v1", "agent-local.tool-semantic.default_v1"),
            Map.entry("agent.summary.default_v1", "agent-local.summary.default_v1"),
            Map.entry("tool.args_v1", "tool.args.default_v1"),
            Map.entry("tool.decision_v1", "tool.decision.default_v1")
    );

    private PromptKeyAliasSupport() {
    }

    public static Set<String> aliasesOf(String key) {
        Set<String> aliases = new LinkedHashSet<>();
        if (key == null || key.isBlank()) {
            return aliases;
        }
        String normalized = key.trim();
        aliases.add(normalized);
        String canonical = toCanonical(normalized);
        aliases.add(canonical);
        aliases.addAll(findLegacyAliases(canonical));
        aliases.add(mapAgentLocalAlias(normalized));
        aliases.add(mapAgentLocalAlias(canonical));
        aliases.removeIf(String::isBlank);
        return aliases;
    }

    public static boolean matches(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        Set<String> leftAliases = aliasesOf(left);
        Set<String> rightAliases = aliasesOf(right);
        for (String alias : leftAliases) {
            for (String target : rightAliases) {
                if (alias.equalsIgnoreCase(target)) {
                    return true;
                }
                if (isNormalizedAliasMatch(alias, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String toCanonical(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String trimmed = key.trim();
        String mapped = LEGACY_TO_CANONICAL.get(trimmed.toLowerCase());
        return mapped == null ? trimmed : mapped;
    }

    private static Set<String> findLegacyAliases(String canonical) {
        Set<String> out = new LinkedHashSet<>();
        if (canonical == null || canonical.isBlank()) {
            return out;
        }
        for (Map.Entry<String, String> entry : LEGACY_TO_CANONICAL.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(canonical)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private static boolean isNormalizedAliasMatch(String requested, String stored) {
        if (requested == null || requested.isBlank() || stored == null || stored.isBlank()) {
            return false;
        }
        if (stored.equalsIgnoreCase(requested)) {
            return true;
        }
        String requestedFull = normalizeKey(requested, false);
        String requestedSlim = normalizeKey(requested, true);
        String storedFull = normalizeKey(stored, false);
        String storedSlim = normalizeKey(stored, true);
        return (!requestedFull.isBlank() && requestedFull.equals(storedFull))
                || (!requestedSlim.isBlank() && requestedSlim.equals(storedFull))
                || (!storedSlim.isBlank() && storedSlim.equals(requestedFull))
                || (!requestedSlim.isBlank() && requestedSlim.equals(storedSlim));
    }

    private static String normalizeKey(String key, boolean removeCategoryPrefix) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String normalized = key.trim().toLowerCase();
        if (removeCategoryPrefix && normalized.contains(".")) {
            String[] parts = normalized.split("\\.");
            if (parts.length > 1) {
                normalized = String.join("", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            }
        }
        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private static String mapAgentLocalAlias(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.regionMatches(true, 0, "agent-local.", 0, "agent-local.".length())) {
            return "agent." + key.substring("agent-local.".length());
        }
        if (key.regionMatches(true, 0, "agent.", 0, "agent.".length())) {
            return "agent-local." + key.substring("agent.".length());
        }
        return key;
    }
}
