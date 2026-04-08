package org.yilena.luna.prompt.governance.support;

import java.util.LinkedHashSet;
import java.util.Set;

public final class PromptKeyAliasSupport {

    private PromptKeyAliasSupport() {
    }

    public static Set<String> aliasesOf(String key) {
        Set<String> aliases = new LinkedHashSet<>();
        if (key == null || key.isBlank()) {
            return aliases;
        }
        String normalized = key.trim();
        aliases.add(normalized);
        String mapped = mapAgentLocalAlias(normalized);
        if (!mapped.equalsIgnoreCase(normalized)) {
            aliases.add(mapped);
        }
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
            }
        }
        return false;
    }

    private static String mapAgentLocalAlias(String key) {
        if (key.regionMatches(true, 0, "agent-local.", 0, "agent-local.".length())) {
            return "agent." + key.substring("agent-local.".length());
        }
        if (key.regionMatches(true, 0, "agent.", 0, "agent.".length())) {
            return "agent-local." + key.substring("agent.".length());
        }
        return key;
    }
}
