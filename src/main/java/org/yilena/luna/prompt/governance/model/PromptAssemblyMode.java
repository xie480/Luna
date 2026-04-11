package org.yilena.luna.prompt.governance.model;

/**
 * 提示词组装模式枚举，负责定义提示词在解析阶段参与组装的触发方式，
 * 用于控制提示词需要始终生效、依赖关键字还是受策略手动控制。
 */
public enum PromptAssemblyMode {
    /**
     * 始终参与组装。
     */
    ALWAYS,
    /**
     * 仅在关键字命中时参与组装。
     */
    KEYWORD_ONLY,
    /**
     * 仅在代理匹配时参与组装。
     */
    AGENT_ONLY,
    /**
     * 同时满足关键字和代理条件时参与组装。
     */
    KEYWORD_AND_AGENT,
    /**
     * 关键字或代理任一满足时参与组装。
     */
    KEYWORD_OR_AGENT,
    /**
     * 仅受策略包含控制参与组装。
     */
    POLICY_ONLY,
    /**
     * 仅在手动指定时参与组装。
     */
    MANUAL_ONLY,
    /**
     * 禁止参与组装。
     */
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
