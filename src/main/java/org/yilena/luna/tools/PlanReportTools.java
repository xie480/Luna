package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.PlanReport;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.PlanFinalStatus;
import org.yilena.luna.enums.PlanOpenResult;
import org.yilena.luna.mapper.PlanReportMapper;

import java.awt.Desktop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Report related tools:
 * - write_html_report_file
 * - open_browser_with_file
 */
@Slf4j
@Component
public class PlanReportTools extends BaseTool {

    private static final String KEY_REPORT_PATH = "reportPath";
    private static final String KEY_REPORT_PATH_NORMALIZED = "reportPathNormalized";
    private static final String KEY_REPORT_URL = "reportUrl";
    private static final String KEY_REPORT_FILE_NAME = "reportFileName";
    private static final String KEY_FINAL_STATUS = "finalStatus";
    private static final String KEY_OPEN_RESULT = "openResult";
    private static final String KEY_COPY_HINT = "copyHint";

    private static final String OPEN_RESULT_NOT_SUPPORTED = "NOT_SUPPORTED";
    private static final String OPEN_RESULT_FAILED = "FAILED";
    private static final String UNKNOWN_ERROR = "unknown error";

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
            if (fn.contains("..")) {
                return error("fileName 非法");
            }

            Path base = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(base);

            Path target = base.resolve(fn).normalize();
            if (!target.startsWith(base)) {
                return error("路径越界");
            }

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

            log.info("write_html_report_file done, planId={}, path={}, reportUrl={}, finalStatus={}, openResult={}",
                    planId, reportPath, reportUrl, safeFinalStatus, safeOpenResult);
            return success(Map.of(
                    KEY_REPORT_PATH, reportPath,
                    KEY_REPORT_PATH_NORMALIZED, target.normalize().toString(),
                    KEY_REPORT_URL, reportUrl,
                    KEY_REPORT_FILE_NAME, target.getFileName().toString(),
                    KEY_FINAL_STATUS, safeFinalStatus.name(),
                    KEY_OPEN_RESULT, safeOpenResult.name(),
                    KEY_COPY_HINT, reportPath
            ));
        } catch (Exception e) {
            log.error("write_html_report_file failed", e);
            return error("write_html_report_file failed: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_REPORT, status = LunaStateConstant.STATUS_REPORT)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "打开报告文件")
    public String openBrowserWithFile(@RequestParam("reportPath") String reportPath) {
        try {
            if (reportPath == null || reportPath.isBlank()) {
                return error("reportPath cannot be empty");
            }

            File file = new File(reportPath);
            if (!file.exists() && reportPath.startsWith("file:/")) {
                Path p = Paths.get(java.net.URI.create(reportPath));
                file = p.toFile();
            }
            if (!file.exists()) {
                return error("file not found: " + reportPath);
            }

            Path normalized = file.toPath().toAbsolutePath().normalize();
            String fileUri = normalized.toUri().toString();

            if (!Desktop.isDesktopSupported()) {
                return success(Map.of(
                        KEY_OPEN_RESULT, OPEN_RESULT_NOT_SUPPORTED,
                        KEY_REPORT_PATH, normalized.toString(),
                        KEY_REPORT_URL, fileUri,
                        KEY_COPY_HINT, normalized.toString()
                ));
            }

            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(normalized.toUri());
            } else if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(normalized.toFile());
            } else {
                return success(Map.of(
                        KEY_OPEN_RESULT, OPEN_RESULT_NOT_SUPPORTED,
                        KEY_REPORT_PATH, normalized.toString(),
                        KEY_REPORT_URL, fileUri,
                        KEY_COPY_HINT, normalized.toString()
                ));
            }

            return success(Map.of(
                    KEY_OPEN_RESULT, PlanOpenResult.SUCCESS.getValue(),
                    KEY_REPORT_PATH, normalized.toString(),
                    KEY_REPORT_URL, fileUri,
                    KEY_COPY_HINT, normalized.toString()
            ));
        } catch (Exception e) {
            log.error("open_browser_with_file failed", e);
            return success(Map.of(
                    KEY_OPEN_RESULT, OPEN_RESULT_FAILED,
                    KEY_REPORT_PATH, reportPath == null ? "" : reportPath,
                    JsonFieldConstants.ERROR, e.getMessage() == null ? UNKNOWN_ERROR : e.getMessage(),
                    KEY_COPY_HINT, reportPath == null ? "" : reportPath
            ));
        }
    }

    private PlanFinalStatus parseFinalStatusOrDefault(String raw, PlanFinalStatus def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return PlanFinalStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    private PlanOpenResult parseOpenResultOrDefault(String raw, PlanOpenResult def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return PlanOpenResult.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }
}
