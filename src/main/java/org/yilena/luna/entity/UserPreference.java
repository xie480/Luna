package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用戶畫像/偏好表
 * 用於存儲用戶的關鍵設定，通常會常駐 System Prompt
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_preference")
public class UserPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 偏好鍵 (如: nickname, birthday, job)
     */
    @TableField("pref_key")
    private String prefKey;

    /**
     * 偏好值 (如: Yilena, 10-27, Developer)
     */
    @TableField("pref_value")
    private String prefValue;

    /**
     * 描述/備註 (用於輔助模型理解該設定的上下文)
     */
    @TableField("description")
    private String description;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
