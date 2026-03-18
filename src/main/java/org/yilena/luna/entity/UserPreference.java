package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户画像/偏好表
 * 用于存储用户的关键设定，通常会常驻 System Prompt
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_preference")
public class UserPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 偏好键 (如: nickname, birthday, job)
     */
    @TableField("pref_key")
    private String prefKey;

    /**
     * 偏好值 (如: Yilena, 10-27, Developer)
     */
    @TableField("pref_value")
    private String prefValue;

    /**
     * 描述/备注 (用于辅助模型理解该设定的上下文)
     */
    @TableField("description")
    private String description;

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
     * 逻辑删除标记 (0: 未删除, 1: 已删除)
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
