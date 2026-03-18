package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MCP 資源定義表 (原 Tool 定義)
 * 存儲工具與技能的元數據，用於 LLM 決策與反射調用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("resources")
public class Resource implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 類型: TOOL (原子工具) 或 SKILL (複合技能)
     */
    @TableField("type")
    private String type;

    /**
     * 工具名稱 (如 "web_search")，LLM 決策時輸出此名稱
     * 必須唯一
     */
    @TableField("name")
    private String name;

    /**
     * 用於 LLM 理解工具用途的詳細描述
     */
    @TableField("description")
    private String description;

    /**
     * 版本號
     */
    @TableField("version")
    private String version;

    /**
     * 負責人
     */
    @TableField("owner")
    private String owner;

    /**
     * Spring Bean 的名稱 (如 "searchTools")
     */
    @TableField("bean_name")
    private String beanName;

    /**
     * 方法名稱 (如 "web_search")
     */
    @TableField("method_name")
    private String methodName;

    /**
     * JSON Schema 字符串 (嚴格定義參數結構)
     */
    @TableField("input_schema")
    private String inputSchema;

    /**
     * 預期輸出結構的 JSON Schema
     */
    @TableField("output_schema")
    private String outputSchema;

    /**
     * 運行模式: SYNC (同步) 或 ASYNC (異步)
     */
    @TableField("run_mode")
    private String runMode;

    /**
     * 是否需要人工審批
     */
    @TableField("requires_approval")
    private Boolean requiresApproval;

    /**
     * 權限/敏感等級: LOW, MEDIUM, HIGH
     */
    @TableField("sensitivity")
    private String sensitivity;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
