package org.yilena.luna.constants;

/**
 * RocketMQ constants.
 */
public class RocketMqConstant {

    // Log async sink.
    public static final String TOPIC_LOG = "luna_log_topic";
    public static final String GROUP_LOG = "luna_log_group";

    // Knowledge embedding async build.
    public static final String TOPIC_KB_ADD = "luna_kb_add_topic";
    public static final String GROUP_KB_ADD = "luna_kb_add_group";

    // Context summary async build.
    public static final String TOPIC_SUMMARY = "luna_summary_topic";
    public static final String GROUP_SUMMARY = "luna_summary_group";

    // Workflow async execution (canonical naming).
    public static final String TOPIC_WORKFLOW_ASYNC = "luna_workflow_async_topic";
    public static final String GROUP_WORKFLOW_ASYNC = "luna_workflow_async_group";

    // Legacy aliases kept for backward compatibility.
    public static final String TOPIC_SKILL_ASYNC = TOPIC_WORKFLOW_ASYNC;
    public static final String GROUP_SKILL_ASYNC = GROUP_WORKFLOW_ASYNC;
}
