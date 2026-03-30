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
 * McpToolDescriptor ??
 */
public class McpToolDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serverCode;
    private String toolName;
    private String title;
    private String description;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private Boolean requiresApproval;
    private String sensitivity;
    private String version;
}
