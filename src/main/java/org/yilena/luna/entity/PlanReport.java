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
 * 计划报告实体，负责保存计划执行完成后的报告结果和打开状态。
 */
public class PlanReport implements Serializable {

    /**
     * 序列化版本号，用于报告对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @TableId(value = "report_id", type = IdType.ASSIGN_ID)
    /**
     * 报告主键。
     */
    private Long reportId;

    @TableField("plan_id")
    /**
     * 报告所属计划 ID。
     */
    private String planId;

    @TableField("session_id")
    /**
     * 报告所属会话 ID。
     */
    private String sessionId;

    @TableField("final_status")
    /**
     * 计划最终结果状态。
     */
    private PlanFinalStatus finalStatus;

    @TableField("report_title")
    /**
     * 报告标题。
     */
    private String reportTitle;

    @TableField("summary")
    /**
     * 报告摘要内容。
     */
    private String summary;

    @TableField("report_path")
    /**
     * 报告在本地或服务器上的存储路径。
     */
    private String reportPath;

    @TableField("report_url")
    /**
     * 报告可访问的外部 URL。
     */
    private String reportUrl;

    @TableField("open_result")
    /**
     * 报告自动打开浏览器的执行结果。
     */
    private PlanOpenResult openResult;

    @TableField("report_html")
    /**
     * 报告的 HTML 内容快照。
     */
    private String reportHtml;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
