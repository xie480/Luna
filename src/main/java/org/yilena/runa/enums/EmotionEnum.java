package org.yilena.runa.enums;

/*
    情感枚举
 */
public enum EmotionEnum {
    Angry,
    Despair,
    Fearful,
    Shocked,
    Shy,
    Smile,
    Soft,
    Solemn;

    public static boolean contains(String emotion) {
        for (EmotionEnum value : values()) {
            if (value.name().equals(emotion)) {
                return true;
            }
        }
        return false;
    }
}
