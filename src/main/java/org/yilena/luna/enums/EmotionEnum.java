package org.yilena.luna.enums;

/**
 * 情绪枚举，用于标记对话场景中的情绪类别。
 */
public enum EmotionEnum {
    /**
     * 愤怒情绪。
     */
    Angry,
    /**
     * 绝望情绪。
     */
    Despair,
    /**
     * 害怕情绪。
     */
    Fearful,
    /**
     * 震惊情绪。
     */
    Shocked,
    /**
     * 害羞情绪。
     */
    Shy,
    /**
     * 微笑情绪。
     */
    Smile,
    /**
     * 温柔情绪。
     */
    Soft,
    /**
     * 庄重情绪。
     */
    Solemn;

    /**
     * 判断给定字符串是否为有效情绪枚举名称。
     */
    public static boolean contains(String emotion) {
        for (EmotionEnum value : values()) {
            if (value.name().equals(emotion)) {
                return true;
            }
        }
        return false;
    }
}
