package org.yilena.luna.enums;

/**
 * 关系运行态枚举，用于描述陪伴型会话中当前的关系阶段或互动氛围。
 */
public enum RelationalRuntimeState {
    /**
     * 冷启动阶段，双方尚未建立熟悉感。
     */
    COLD_START,
    /**
     * 熟悉建立阶段。
     */
    FAMILIARIZING,
    /**
     * 信任建立阶段。
     */
    TRUST_BUILDING,
    /**
     * 陪伴模式阶段。
     */
    COMPANION_MODE,
    /**
     * 轻松闲聊阶段。
     */
    LIGHT_CHAT,
    /**
     * 深度交流阶段。
     */
    DEEP_TALK,
    /**
     * 情绪支持阶段。
     */
    EMOTIONAL_SUPPORT,
    /**
     * 脆弱时刻阶段。
     */
    FRAGILE_MOMENT,
    /**
     * 关系修复阶段。
     */
    REPAIRING,
    /**
     * 庆祝或正反馈阶段。
     */
    CELEBRATING
}
