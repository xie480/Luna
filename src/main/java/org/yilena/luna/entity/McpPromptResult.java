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
public class McpPromptResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String status;
    private String serverCode;
    private String promptName;
    private Map<String, Object> promptContent;
}
