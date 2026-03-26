package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.PlanReport;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.PlanFinalStatus;
import org.yilena.luna.enums.PlanOpenResult;
import org.yilena.luna.mapper.PlanReportMapper;

import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/**
 * 报告相关工具：
 * - write_html_report_file
 * - open_browser_with_file
 */
@Slf4j
@Component
public class PlanReportTools extends BaseTool {

    private final PlanReportMapper planReportMapper;

    public PlanReportTools(ObjectMapper objectMapper, PlanReportMapper planReportMapper) {
        super(objectMapper);
        this.planReportMapper = planReportMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_REPORT, status = LunaStateConstant.STATUS_REPORT)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "写入HTML报告")
    public String writeHtmlReportFile(
            @RequestParam("planId") String planId,
            @RequestParam("htmlContent") String htmlContent,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "outputDir", required = false) String outputDir,
            @RequestParam(value = "finalStatus", required = false) String finalStatus,
            @RequestParam(value = "openResult", required = false) String openResult
    ) {
        try {
            String dir = (outputDir == null || outputDir.isBlank()) ? "./data/reports" : outputDir;
            String fn = (fileName == null || fileName.isBlank()) ? (planId + ".html") : fileName;
            if (fn.contains("..")) return error("fileName 非法");

            Path base = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(base);

            Path target = base.resolve(fn).normalize();
            if (!target.startsWith(base)) return error("路径越界");

            Path tmp = base.resolve(fn + ".tmp");
            Files.writeString(tmp, htmlContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            String reportPath = target.toString();
            String reportUrl = target.toUri().toString();

            PlanFinalStatus safeFinalStatus = parseFinalStatusOrDefault(finalStatus, PlanFinalStatus.PARTIAL);
            PlanOpenResult safeOpenResult = parseOpenResultOrDefault(openResult, PlanOpenResult.FAILED);

            PlanReport report = PlanReport.builder()
                    .planId(planId)
                    .reportPath(reportPath)
                    .reportUrl(reportUrl)
                    .reportHtml(htmlContent)
                    .finalStatus(safeFinalStatus)
                    .openResult(safeOpenResult)
                    .build();
            planReportMapper.insert(report);

            log.info("write_html_report_file 完成, planId={}, path={}, finalStatus={}, openResult={}",
                    planId, reportPath, safeFinalStatus, safeOpenResult);
            return success(Map.of(
                    "reportPath", reportPath,
                    "reportUrl", reportUrl,
                    "finalStatus", safeFinalStatus.name(),
                    "openResult", safeOpenResult.name()
            ));
        } catch (Exception e) {
            log.error("write_html_report_file 失败", e);
            return error("write_html_report_file 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_REPORT, status = LunaStateConstant.STATUS_REPORT)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "打开报告文件")
    public String openBrowserWithFile(@RequestParam("reportPath") String reportPath) {
        try {
            File file = new File(reportPath);
            if (!file.exists()) return error("文件不存在");

            if (!Desktop.isDesktopSupported()) {
                return success(Map.of("openResult", "FAILED", "error", "Desktop 不支持"));
            }

            Desktop.getDesktop().browse(file.toURI());
            log.info("open_browser_with_file 完成, path={}", reportPath);
            return success(Map.of("openResult", "SUCCESS"));
        } catch (Exception e) {
            log.error("open_browser_with_file 失败", e);
            return success(Map.of("openResult", "FAILED", "error", e.getMessage()));
        }
    }

    private PlanFinalStatus parseFinalStatusOrDefault(String raw, PlanFinalStatus def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return PlanFinalStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    private PlanOpenResult parseOpenResultOrDefault(String raw, PlanOpenResult def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return PlanOpenResult.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }
}
