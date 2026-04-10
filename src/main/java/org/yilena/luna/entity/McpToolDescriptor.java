package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * MCP 工具描述实体，用于保存工具注册信息，支撑工具发现、审批判断和参数校验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 提供该工具的 MCP 服务编码。
     */
    private String serverCode;

    /**
     * 工具唯一名称，用于路由调用目标。
     */
    private String toolName;

    /**
     * 工具展示标题。
     */
    private String title;

    /**
     * 工具能力描述。
     */
    private String description;

    /**
     * 工具输入参数结构定义。
     */
    private Map<String, Object> inputSchema;

    /**
     * 工具输出结果结构定义。
     */
    private Map<String, Object> outputSchema;

    /**
     * 是否需要在执行前发起审批。
     */
    private Boolean requiresApproval;

    /**
     * 工具敏感级别，用于控制调用策略。
     */
    private String sensitivity;

    /**
     * 工具版本号。
     */
    private String version;
}
