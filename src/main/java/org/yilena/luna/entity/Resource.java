package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * MCP 資源 DTO (Data Transfer Object)
 * 用於 Agent 層統一處理 Tool 和 Skill，不直接對應數據庫表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Resource implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /**
     * 類型: "TOOL" 或 "SKILL"
     */
    private String type;

    private String name;
    private String description;
    private String version;
    private String owner;

    private String beanName;
    private String methodName;

    private String inputSchema;
    private String outputSchema;

    // Skill 特有字段 (Tool 默認為 SYNC/FALSE/LOW)
    private String runMode;
    private Boolean requiresApproval;
    private String sensitivity;
}
