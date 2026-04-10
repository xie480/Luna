package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * MCP 资源描述实体，用于记录服务端暴露的资源元信息，便于平台侧发现和读取资源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpResourceDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 提供该资源的 MCP 服务编码。
     */
    private String serverCode;

    /**
     * 资源唯一 URI，用于读取资源时精确定位目标。
     */
    private String resourceUri;

    /**
     * 资源展示名称。
     */
    private String name;

    /**
     * 资源用途说明。
     */
    private String description;

    /**
     * 资源内容的 MIME 类型。
     */
    private String mimeType;

    /**
     * 资源附加注解信息，用于扩展资源元数据。
     */
    private Map<String, Object> annotations;
}
