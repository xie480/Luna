package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * MCP 工具调用结果实体，负责承接工具执行后的状态、路由信息和返回内容。
 */
public class McpToolCallResult implements Serializable {

    /**
     * 序列化版本号，用于调用结果缓存和传输兼容。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 工具调用状态，例如成功、失败或等待中。
     */
    private String status;
    /**
     * 实际执行本次调用的 MCP 服务编码。
     */
    private String serverCode;
    /**
     * 实际执行的工具名称。
     */
    private String toolName;
    /**
     * 工具返回的结构化结果数据。
     */
    private Map<String, Object> data;
    /**
     * 工具返回的原始字符串结果，便于保留完整输出。
     */
    private String rawResult;
}
