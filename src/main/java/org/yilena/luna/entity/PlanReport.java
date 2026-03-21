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
public class PlanReport implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "report_id", type = IdType.ASSIGN_ID)
    private Long reportId;

    @TableField("plan_id")
    private String planId;

    @TableField("session_id")
    private String sessionId;

    @TableField("final_status")
    private PlanFinalStatus finalStatus;

    @TableField("report_title")
    private String reportTitle;

    @TableField("summary")
    private String summary;

    @TableField("report_path")
    private String reportPath;

    @TableField("report_url")
    private String reportUrl;

    @TableField("open_result")
    private PlanOpenResult openResult;

    @TableField("report_html")
    private String reportHtml;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
