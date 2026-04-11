package org.yilena.luna.constants;

/**
 * 布尔语义文本常量类，负责统一维护布尔型文本和数字等价值，
 * 避免各处重复硬编码真假语义标识。
 */
public final class BooleanTextConstants {

    private BooleanTextConstants() {
    }

    /**
     * 真值文本。
     */
    public static final String TRUE = "true";
    /**
     * 假值文本。
     */
    public static final String FALSE = "false";
    /**
     * 表示肯定的文本值。
     */
    public static final String YES = "yes";
    /**
     * 表示否定的文本值。
     */
    public static final String NO = "no";
    /**
     * 表示真值的数字文本。
     */
    public static final String ONE = "1";
    /**
     * 表示假值的数字文本。
     */
    public static final String ZERO = "0";
    /**
     * 表示真值的整型数值。
     */
    public static final int INT_ONE = 1;
    /**
     * 表示假值的整型数值。
     */
    public static final int INT_ZERO = 0;
}
