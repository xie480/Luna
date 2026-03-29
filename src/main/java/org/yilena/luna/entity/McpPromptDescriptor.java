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
public class McpPromptDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serverCode;
    private String promptName;
    private String title;
    private String description;
    private Map<String, Object> argumentsSchema;
    private String version;
}
