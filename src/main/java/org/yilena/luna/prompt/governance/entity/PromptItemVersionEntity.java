package org.yilena.luna.prompt.governance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 提示词版本实体，用于管理 Prompt 文本内容的历史版本、匹配规则和编辑策略。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "prompt_item_version", autoResultMap = true)
public class PromptItemVersionEntity implements Serializable {

    /**
     * Prompt 版本主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属 Prompt 条目 ID。
     */
    @TableField("prompt_item_id")
    private Long promptItemId;

    /**
     * 版本号，用于版本控制和回溯。
     */
    @TableField("version_no")
    private String versionNo;

    /**
     * 版本标签，用于界面侧展示版本语义。
     */
    @TableField("version_label")
    private String versionLabel;

    /**
     * 当前版本的 Prompt 文本内容。
     */
    @TableField("prompt_value")
    private String promptValue;

    /**
     * 模板变量列表，用于约束运行时可替换参数。
     */
    @TableField(value = "template_variables", typeHandler = JsonbTypeHandler.class)
    private List<String> templateVariables;

    /**
     * 匹配关键词列表，用于命中关键词路由。
     */
    @TableField(value = "match_keywords", typeHandler = JsonbTypeHandler.class)
    private List<String> matchKeywords;

    /**
     * 匹配范围配置，用于补充关键词命中的限制条件。
     */
    @TableField(value = "match_scope", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> matchScope;

    /**
     * 编辑策略配置，用于控制该版本的可编辑范围和规则。
     */
    @TableField(value = "edit_policy", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> editPolicy;

    /**
     * 当前版本状态。
     */
    @TableField("status")
    private String status;

    /**
     * 版本变更说明。
     */
    @TableField("change_note")
    private String changeNote;

    /**
     * 是否为当前激活版本。
     */
    @TableField("is_active")
    private Boolean isActive;

    /**
     * 版本创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 版本最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
