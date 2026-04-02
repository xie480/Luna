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
 * Unified capability DTO used by the host layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Resource implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private ResourceType type;
    private String name;
    private String serverCode;
    private String description;
    private String resourceUri;
    private String mimeType;
    private String version;
    private String owner;

    private String inputSchema;
    private String outputSchema;
    private String argumentsSchema;

    private RunMode runMode;
    private Boolean requiresApproval;
    private Sensitivity sensitivity;

    private List<String> requiredCapabilities;
    private List<ToolSlotDto> toolSlots;
    private List<String> thoughtChain;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * ToolSlotDto ??
     */
    public static class ToolSlotDto implements Serializable {
        private String slot;
        private String capability;
        private Boolean required;
    }
}
