package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;

import java.io.Serializable;

/**
 * MCP 资源 DTO (Data Transfer Object)
 * 用于 Agent 层统一处理 Tool 和 Skill，不直接对应数据库表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Resource implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 资源唯一 ID
     */
    private String id;

    /**
     * 资源类型: TOOL (原子工具) 或 SKILL (复合技能)
     */
    private ResourceType type;

    /**
     * 资源名称 (如 "web_search")
     */
    private String name;

    /**
     * 资源描述 (用于 LLM 理解用途)
     */
    private String description;

    /**
     * 版本号
     */
    private String version;

    /**
     * 负责人/所有者
     */
    private String owner;

    /**
     * Spring Bean 的名称
     */
    private String beanName;

    /**
     * 执行方法名称
     */
    private String methodName;

    /**
     * 输入参数的 JSON Schema
     */
    private String inputSchema;

    /**
     * 输出结果的 JSON Schema
     */
    private String outputSchema;

    /**
     * 运行模式 (Skill 特有字段，Tool 默认为 SYNC)
     */
    private RunMode runMode;

    /**
     * 是否需要人工审批 (Skill 特有字段，Tool 默认为 false)
     */
    private Boolean requiresApproval;

    /**
     * 敏感度/权限等级 (Skill 特有字段，Tool 默认为 LOW)
     */
    private Sensitivity sensitivity;
}
