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
/**
 * 计划报告工具类，负责落盘 HTML 报告、登记报告记录，并在本地环境中尝试打开最终报告文件。
 */
public class PlanReportTools extends BaseTool {

    /**
     * 报告路径字段名，用于统一返回结构。
     */
    private static final String KEY_REPORT_PATH = "reportPath";
    /**
     * 规范化后的报告路径字段名。
     */
    private static final String KEY_REPORT_PATH_NORMALIZED = "reportPathNormalized";
    /**
     * 报告 URL 字段名。
     */
    private static final String KEY_REPORT_URL = "reportUrl";
    /**
     * 报告文件名字段名。
     */
    private static final String KEY_REPORT_FILE_NAME = "reportFileName";
    /**
     * 最终执行状态字段名。
     */
    private static final String KEY_FINAL_STATUS = "finalStatus";
    /**
     * 打开结果字段名。
     */
    private static final String KEY_OPEN_RESULT = "openResult";
    /**
     * 给调用方展示的本地复制提示字段名。
     */
    private static final String KEY_COPY_HINT = "copyHint";

    /**
     * 当前环境不支持自动打开文件时的状态值。
     */
    private static final String OPEN_RESULT_NOT_SUPPORTED = "NOT_SUPPORTED";
    /**
     * 自动打开失败时的状态值。
     */
    private static final String OPEN_RESULT_FAILED = "FAILED";
    /**
     * 未获取到明确异常信息时的兜底文案。
     */
    private static final String UNKNOWN_ERROR = "unknown error";

    /**
     * 计划报告数据访问对象，用于持久化报告元数据和 HTML 内容。
     */
    private final PlanReportMapper planReportMapper;

    public PlanReportTools(ObjectMapper objectMapper, PlanReportMapper planReportMapper) {
        super(objectMapper);
        this.planReportMapper = planReportMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_REPORT, status = LunaStateConstant.STATUS_REPORT)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "写入HTML报告")
    /**
     * 写入计划 HTML 报告文件，并同步登记报告记录，供后续下载、打开和回溯使用。
     */
    public String writeHtmlReportFile(
            @RequestParam("planId") String planId,
            @RequestParam("htmlContent") String htmlContent,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "outputDir", required = false) String outputDir,
            @RequestParam(value = "finalStatus", required = false) String finalStatus,
            @RequestParam(value = "openResult", required = false) String openResult
    ) {
        try {
            /**
             * 先确定输出目录和文件名，并拦截路径穿越风险，确保报告只落到受控位置。
             */
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

            /**
             * 先写入临时文件再原子替换正式文件，避免报告生成过程中出现半写入状态。
             */
            Path tmp = base.resolve(fn + ".tmp");
            Files.writeString(tmp, htmlContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            String reportPath = target.toString();
            String reportUrl = target.toUri().toString();

            PlanFinalStatus safeFinalStatus = parseFinalStatusOrDefault(finalStatus, PlanFinalStatus.PARTIAL);
            PlanOpenResult safeOpenResult = parseOpenResultOrDefault(openResult, PlanOpenResult.FAILED);

            /**
             * 文件写入成功后同步保存报告记录，便于后续展示最终状态和访问地址。
             */
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
    /**
     * 打开本地报告文件，优先使用浏览器打开，不支持时返回可复制的本地路径。
     */
    public String openBrowserWithFile(@RequestParam("reportPath") String reportPath) {
        try {
            if (reportPath == null || reportPath.isBlank()) {
                return error("reportPath cannot be empty");
            }

            /**
             * 先解析并校验报告路径，兼容 file URI 和普通本地路径两种输入形式。
             */
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

            /**
             * 当前环境不支持桌面能力时直接返回路径提示，交由调用方自行处理。
             */
            if (!Desktop.isDesktopSupported()) {
                return success(Map.of(
                        KEY_OPEN_RESULT, OPEN_RESULT_NOT_SUPPORTED,
                        KEY_REPORT_PATH, normalized.toString(),
                        KEY_REPORT_URL, fileUri,
                        KEY_COPY_HINT, normalized.toString()
                ));
            }

            /**
             * 优先走浏览器打开，浏览器不可用时再尝试使用系统默认程序打开文件。
             */
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

    /**
     * 解析最终状态枚举，非法输入时回退到默认值。
     */
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

    /**
     * 解析报告打开结果枚举，非法输入时回退到默认值。
     */
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
