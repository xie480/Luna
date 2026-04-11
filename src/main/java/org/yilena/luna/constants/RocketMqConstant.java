package org.yilena.luna.constants;

/**
 * RocketMQ 常量类，统一维护日志、知识、摘要和工作流异步任务的主题与消费组名称。
 */
public class RocketMqConstant {

    /**
     * 异步日志落库主题。
     */
    public static final String TOPIC_LOG = "luna_log_topic";

    /**
     * 异步日志落库消费组。
     */
    public static final String GROUP_LOG = "luna_log_group";

    /**
     * 知识异步入库主题。
     */
    public static final String TOPIC_KB_ADD = "luna_kb_add_topic";

    /**
     * 知识异步入库消费组。
     */
    public static final String GROUP_KB_ADD = "luna_kb_add_group";

    /**
     * 上下文摘要异步生成主题。
     */
    public static final String TOPIC_SUMMARY = "luna_summary_topic";

    /**
     * 上下文摘要异步生成消费组。
     */
    public static final String GROUP_SUMMARY = "luna_summary_group";

    /**
     * 工作流异步执行主题。
     */
    public static final String TOPIC_WORKFLOW_ASYNC = "luna_workflow_async_topic";

    /**
     * 工作流异步执行消费组。
     */
    public static final String GROUP_WORKFLOW_ASYNC = "luna_workflow_async_group";

    /**
     * 兼容旧版技能异步主题名的别名。
     */
    public static final String TOPIC_SKILL_ASYNC = TOPIC_WORKFLOW_ASYNC;

    /**
     * 兼容旧版技能异步消费组的别名。
     */
    public static final String GROUP_SKILL_ASYNC = GROUP_WORKFLOW_ASYNC;

    private RocketMqConstant() {
    }
}
