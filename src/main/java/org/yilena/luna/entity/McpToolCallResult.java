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
 * McpToolCallResult ??
 */
public class McpToolCallResult implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    private String status; // 声明成员字段
    private String serverCode; // 声明成员字段
    private String toolName; // 声明成员字段
    private Map<String, Object> data; // 声明成员字段
    private String rawResult; // 声明成员字段
} // 结束当前代码块
