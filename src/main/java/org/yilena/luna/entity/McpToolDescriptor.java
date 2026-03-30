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

    private static final long serialVersionUID = 1L; // 声明成员字段

    private String serverCode; // 声明成员字段
    private String toolName; // 声明成员字段
    private String title; // 声明成员字段
    private String description; // 声明成员字段
    private Map<String, Object> inputSchema; // 声明成员字段
    private Map<String, Object> outputSchema; // 声明成员字段
    private Boolean requiresApproval; // 声明成员字段
    private String sensitivity; // 声明成员字段
    private String version; // 声明成员字段
} // 结束当前代码块
