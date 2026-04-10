package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;

import java.io.Serializable;
import java.util.List;

/**
 * 统一能力资源对象，负责在宿主层统一表示工具、提示词、资源和工作流等能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Resource implements Serializable {

    /**
     * 序列化版本号，用于能力对象传输和缓存兼容。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 能力资源唯一标识。
     */
    private String id;
    /**
     * 能力资源类型。
     */
    private ResourceType type;
    /**
     * 能力名称。
     */
    private String name;
    /**
     * 能力所属服务编码。
     */
    private String serverCode;
    /**
     * 能力用途描述。
     */
    private String description;
    /**
     * 资源 URI，适用于可读取资源类型。
     */
    private String resourceUri;
    /**
     * 资源 MIME 类型。
     */
    private String mimeType;
    /**
     * 能力版本号。
     */
    private String version;
    /**
     * 执行模式，例如 MCP 或兼容旧模式。
     */
    private String executionMode;
    /**
     * 能力归属方或维护人。
     */
    private String owner;

    /**
     * 输入结构定义 JSON。
     */
    private String inputSchema;
    /**
     * 输出结构定义 JSON。
     */
    private String outputSchema;
    /**
     * 参数结构定义 JSON，常用于提示词参数描述。
     */
    private String argumentsSchema;

    /**
     * 运行模式，例如同步或异步。
     */
    private RunMode runMode;
    /**
     * 是否需要审批后才能执行。
     */
    private Boolean requiresApproval;
    /**
     * 能力敏感等级。
     */
    private Sensitivity sensitivity;

    /**
     * 执行该能力前所需的能力依赖列表。
     */
    private List<String> requiredCapabilities;
    /**
     * 工作流插槽定义列表。
     */
    private List<ToolSlotDto> toolSlots;
    /**
     * 工作流思维链或执行提示列表。
     */
    private List<String> thoughtChain;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 工具插槽定义对象，负责描述工作流中某个能力位的约束。
     */
    public static class ToolSlotDto implements Serializable {
        /**
         * 插槽名称。
         */
        private String slot;
        /**
         * 插槽要求的能力名称或类型。
         */
        private String capability;
        /**
         * 当前插槽是否必填。
         */
        private Boolean required;
    }
}
