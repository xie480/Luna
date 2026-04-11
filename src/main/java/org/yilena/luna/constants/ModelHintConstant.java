package org.yilena.luna.constants;

/**
 * 模型提示常量类，定义模型输出和中间结构中常用的语义字段键名。
 */
public final class ModelHintConstant {

    /**
     * 用户输入字段名。
     */
    public static final String USER_INPUT = "USER_INPUT";

    /**
     * 未明确分类时的默认标识。
     */
    public static final String UNSPECIFIED = "UNSPECIFIED";

    /**
     * 情绪字段名。
     */
    public static final String EMOTION = "emotion";

    /**
     * 回复内容字段名。
     */
    public static final String REPLY = "reply";

    /**
     * 置信度字段名。
     */
    public static final String CONFIDENCE = "confidence";

    private ModelHintConstant() {
    }
}
