package org.yilena.luna.prompt.governance.model;

public enum PromptAssemblyMode {
    ALWAYS,
    KEYWORD_ONLY,
    AGENT_ONLY,
    KEYWORD_AND_AGENT,
    KEYWORD_OR_AGENT,
    POLICY_ONLY,
    MANUAL_ONLY,
    DISABLED;

    public static PromptAssemblyMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALWAYS;
        }
        try {
            return PromptAssemblyMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignore) {
            return ALWAYS;
        }
    }
}

