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
 * McpResourceDescriptor ??
 */
public class McpResourceDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serverCode;
    private String resourceUri;
    private String name;
    private String description;
    private String mimeType;
    private Map<String, Object> annotations;
}
