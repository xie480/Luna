package org.yilena.luna.enums; // define package

/*
    情感枚举 // business logic
 */
public enum EmotionEnum { // define enum
    Angry, // enum or const item
    Despair, // enum or const item
    Fearful, // enum or const item
    Shocked, // enum or const item
    Shy, // enum or const item
    Smile, // enum or const item
    Soft, // enum or const item
    Solemn; // enum or const item

    public static boolean contains(String emotion) { // method definition
        for (EmotionEnum value : values()) { // loop logic
            if (value.name().equals(emotion)) { // branch logic
                return true; // return result
            } // block end
        } // block end
        return false; // return result
    } // block end
} // block end
