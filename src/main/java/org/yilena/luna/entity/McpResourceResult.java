package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * MCP 资源读取结果实体，用于封装资源拉取状态、来源信息和实际返回数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpResourceResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 资源读取结果状态。
     */
    private String status;

    /**
     * 实际提供资源的 MCP 服务编码。
     */
    private String serverCode;

    /**
     * 被读取资源的唯一 URI。
     */
    private String resourceUri;

    /**
     * 返回内容的 MIME 类型。
     */
    private String mimeType;

    /**
     * 资源返回的结构化数据载荷。
     */
    private Map<String, Object> data;
}
