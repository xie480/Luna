package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * MCP Prompt 调用结果实体，用于封装提示词执行后的状态、来源和返回内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPromptResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 调用结果状态，例如成功或失败。
     */
    private String status;

    /**
     * 实际执行该 Prompt 的 MCP 服务编码。
     */
    private String serverCode;

    /**
     * 被调用的 Prompt 名称。
     */
    private String promptName;

    /**
     * Prompt 生成的结构化内容。
     */
    private Map<String, Object> promptContent;
}
