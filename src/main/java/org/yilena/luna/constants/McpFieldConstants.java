package org.yilena.luna.constants;

/**
 * MCP 字段常量类，负责统一维护 MCP 目录、能力注册和资源映射中常用的字段名，
 * 供目录同步、映射转换和查询封装时复用。
 */
public final class McpFieldConstants {

    private McpFieldConstants() {
    }

    /**
     * 能力类型字段名。
     */
    public static final String CAPABILITY_TYPE = "capability_type";
    /**
     * 能力名称字段名。
     */
    public static final String CAPABILITY_NAME = "capability_name";
    /**
     * 描述字段名。
     */
    public static final String DESCRIPTION = "description";
    /**
     * 敏感级别字段名。
     */
    public static final String SENSITIVITY = "sensitivity";
    /**
     * 是否需要审批字段名。
     */
    public static final String REQUIRES_APPROVAL = "requires_approval";
    /**
     * 输入结构字段名。
     */
    public static final String INPUT_SCHEMA = "input_schema";
    /**
     * 输出结构字段名。
     */
    public static final String OUTPUT_SCHEMA = "output_schema";
    /**
     * 标题字段名。
     */
    public static final String TITLE = "title";
    /**
     * 元数据字段名。
     */
    public static final String METADATA_JSON = "metadata_json";
    /**
     * 工作流名称字段名。
     */
    public static final String WORKFLOW_NAME = "workflow_name";
    /**
     * 过程类型字段名。
     */
    public static final String PROCEDURE_TYPE = "procedure_type";
    /**
     * 领域字段名。
     */
    public static final String DOMAIN = "domain";
    /**
     * 资源地址字段名。
     */
    public static final String RESOURCE_URI = "resource_uri";
    /**
     * 调用名称字段名。
     */
    public static final String INVOCATION_NAME = "invocation_name";
    /**
     * 工具名称字段名。
     */
    public static final String TOOL_NAME = "tool_name";
    /**
     * 提示词名称字段名。
     */
    public static final String PROMPT_NAME = "prompt_name";
    /**
     * 所需能力字段名。
     */
    public static final String REQUIRED_CAPABILITIES = "required_capabilities";
    /**
     * 工具槽位字段名。
     */
    public static final String TOOL_SLOTS = "tool_slots";
    /**
     * 模式步骤 JSON 字段名。
     */
    public static final String PATTERN_STEPS_JSON = "pattern_steps_json";
    /**
     * 服务端编码字段名。
     */
    public static final String SERVER_CODE = "server_code";
}
