package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.handler.VectorTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户偏好实体，用于存储用户画像、个人设定和长期偏好信息，为系统提示词和个性化响应提供依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "user_preference", autoResultMap = true)
public class UserPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 偏好主键 ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 偏好键，用于标识偏好项类型，例如昵称、职业等。
     */
    @TableField("pref_key")
    private String prefKey;

    /**
     * 偏好值，用于保存具体设定内容。
     */
    @TableField("pref_value")
    private String prefValue;

    /**
     * 偏好说明，用于补充该设定的上下文语义。
     */
    @TableField("description")
    private String description;

    /**
     * 偏好内容的向量表示，用于语义检索和匹配。
     */
    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private String embedding;

    /**
     * 偏好创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 偏好最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记，0 表示未删除，1 表示已删除。
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
