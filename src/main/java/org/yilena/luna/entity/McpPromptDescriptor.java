package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * MCP Prompt 描述实体，用于承接服务端注册的提示词元数据并提供给平台侧展示与调用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPromptDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 提供该 Prompt 的 MCP 服务编码。
     */
    private String serverCode;

    /**
     * Prompt 唯一名称，用于发起调用时定位目标提示词。
     */
    private String promptName;

    /**
     * Prompt 展示标题。
     */
    private String title;

    /**
     * Prompt 功能说明。
     */
    private String description;

    /**
     * Prompt 入参结构定义，用于描述调用所需参数。
     */
    private Map<String, Object> argumentsSchema;

    /**
     * Prompt 版本号，用于区分不同发布版本。
     */
    private String version;
}
