package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 技能实体 (对应 mcp_skills 表)
 * 复合能力，支持异步执行
 *
 * 【v2.3 重构】对齐 Skill 新协议：
 * - requiredCapabilities: 技能所需能力集合（JSONB 数组）
 * - toolSlots: 能力槽位定义（JSONB 数组对象）
 * - thoughtChain: 编排思维链（JSONB 字符串数组）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "mcp_skills", autoResultMap = true)
public class McpSkill implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID (雪花算法)
     * 使用 ToStringSerializer 防止前端 JS 精度丢失
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 技能名称 (需唯一，供 LLM 决策时输出此名称)
     */
    @TableField("name")
    private String name;

    /**
     * 技能描述 (用于向 LLM 解释技能的具体用途和调用时机)
     */
    @TableField("description")
    private String description;

    /**
     * 版本号 (如 "1.0.0")
     */
    @TableField("version")
    private String version;

    /**
     * 负责人/所有者
     */
    @TableField("owner")
    private String owner;

    /**
     * 对应的 Spring Bean 名称 (或工作流引擎 ID)
     */
    @TableField("bean_name")
    private String beanName;

    /**
     * 对应的执行方法名称
     */
    @TableField("method_name")
    private String methodName;

    /**
     * 输入参数的 JSON Schema 定义 (用于严格校验 LLM 生成的参数)
     */
    @TableField("input_schema")
    private String inputSchema;

    /**
     * 输出结果的 JSON Schema 定义
     */
    @TableField("output_schema")
    private String outputSchema;

    /**
     * 执行模式 (同步 SYNC / 异步 ASYNC)
     */
    @TableField("run_mode")
    private RunMode runMode;

    /**
     * 技能所需能力集合（JSONB 字符串数组）
     * 示例: ["WEB_SEARCH","WEB_FETCH","KB_INSERT"]
     */
    @TableField(value = "required_capabilities", typeHandler = JsonbTypeHandler.class)
    private List<String> requiredCapabilities;

    /**
     * 能力槽位定义（JSONB 数组对象）
     * 示例:
     * [
     *   {"slot":"search","capability":"WEB_SEARCH","required":true},
     *   {"slot":"fetch","capability":"WEB_FETCH","required":true}
     * ]
     */
    @TableField(value = "tool_slots", typeHandler = JsonbTypeHandler.class)
    private List<ToolSlot> toolSlots;

    /**
     * Skill 编排思维链（JSONB 字符串数组）
     */
    @TableField(value = "thought_chain", typeHandler = JsonbTypeHandler.class)
    private List<String> thoughtChain;

    /**
     * 文本的向量表示 (PGVector)，用于语义检索
     */
    @TableField("embedding")
    private String embedding;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 技能槽位对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolSlot implements Serializable {
        private String slot;
        private String capability;
        private Boolean required;
    }
}
