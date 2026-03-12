package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 長期記憶實體類
 * 對應數據庫表：luna_memory
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("luna_memory")
public class Memory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主鍵 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 會話 ID 或日期標識（例如：2023:10:27）
     * 用於關聯記憶與特定的時間段或會話
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 記憶類型
     * 例如：FACT (事實), PREFERENCE (偏好), SUMMARY (摘要), REFLECTION (反思)
     */
    @TableField("memory_type")
    private String memoryType;

    /**
     * 記憶內容
     * 存儲具體的文本信息
     */
    @TableField("content")
    private String content;

    /**
     * 權重
     * 用於標識記憶的重要性，默認為 1
     */
    @TableField("weight")
    private Integer weight;

    /**
     * 創建時間
     * 插入時自動填充
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新時間
     * 插入和更新時自動填充
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
