package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.PlanFinalStatus;
import org.yilena.luna.enums.PlanOpenResult;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("plan_report")
/**
 * PlanReport ??
 */
public class PlanReport implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "report_id", type = IdType.ASSIGN_ID) // 声明注解
    private Long reportId; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("session_id") // 声明注解
    private String sessionId; // 声明成员字段

    @TableField("final_status") // 声明注解
    private PlanFinalStatus finalStatus; // 声明成员字段

    @TableField("report_title") // 声明注解
    private String reportTitle; // 声明成员字段

    @TableField("summary") // 声明注解
    private String summary; // 声明成员字段

    @TableField("report_path") // 声明注解
    private String reportPath; // 声明成员字段

    @TableField("report_url") // 声明注解
    private String reportUrl; // 声明成员字段

    @TableField("open_result") // 声明注解
    private PlanOpenResult openResult; // 声明成员字段

    @TableField("report_html") // 声明注解
    private String reportHtml; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
