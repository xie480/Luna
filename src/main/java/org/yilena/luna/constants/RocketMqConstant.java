package org.yilena.luna.constants;

/**
 * RocketMQ 常量定義
 */
public class RocketMqConstant {
    
    // 日誌異步落庫
    public static final String TOPIC_LOG = "luna_log_topic";
    public static final String GROUP_LOG = "luna_log_group";

    // 知識庫異步寫入 (Embedding)
    public static final String TOPIC_KB_ADD = "luna_kb_add_topic";
    public static final String GROUP_KB_ADD = "luna_kb_add_group";

    // 上下文異步壓縮摘要
    public static final String TOPIC_SUMMARY = "luna_summary_topic";
    public static final String GROUP_SUMMARY = "luna_summary_group";

    // 技能異步執行
    public static final String TOPIC_SKILL_ASYNC = "luna_skill_async_topic";
    public static final String GROUP_SKILL_ASYNC = "luna_skill_async_group";
}
