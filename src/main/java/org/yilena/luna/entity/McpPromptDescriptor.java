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
 * McpPromptDescriptor ??
 */
public class McpPromptDescriptor implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    private String serverCode; // 声明成员字段
    private String promptName; // 声明成员字段
    private String title; // 声明成员字段
    private String description; // 声明成员字段
    private Map<String, Object> argumentsSchema; // 声明成员字段
    private String version; // 声明成员字段
} // 结束当前代码块
