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
import org.yilena.luna.enums.LogType;

import java.util.List;
import java.util.Map;

/**
 * 桌面能力工具：
 * - capture_desktop_screenshot
 * - detect_ui_elements
 */
@Slf4j
@Component
/**
 * 桌面工具类，负责对外暴露桌面截图与界面元素识别入口，当前主要作为桌面自动化能力的占位实现。
 */
public class DesktopTools extends BaseTool {

    public DesktopTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_DESKTOP, status = LunaStateConstant.STATUS_DESKTOP)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "抓取桌面截图")
    /**
     * 截取指定显示器或区域的桌面图像，当前返回占位结果用于保持工具协议稳定。
     */
    public String captureDesktopScreenshot(
            @RequestParam(value = "monitorIndex", required = false) Integer monitorIndex,
            @RequestParam(value = "region", required = false) String region
    ) {
        log.info("capture_desktop_screenshot 占位执行, monitorIndex={}, region={}", monitorIndex, region);
        /**
         * 在桌面能力尚未接入时返回固定结构，避免调用方因字段缺失而中断流程。
         */
        return success(Map.of(
                "imagePath", "",
                "width", 0,
                "height", 0,
                "monitorIndex", monitorIndex,
                "region", region,
                "message", "NOT_IMPLEMENTED"
        ));
    }

    @LunaState(value = LunaStateConstant.VALUE_DESKTOP, status = LunaStateConstant.STATUS_DESKTOP)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "识别UI元素")
    /**
     * 识别截图中的 UI 元素，当前返回空结果作为后续视觉识别链路的兼容占位。
     */
    public String detectUiElements(
            @RequestParam(value = "imagePath", required = false) String imagePath,
            @RequestParam(value = "detectorType", required = false) String detectorType,
            @RequestParam(value = "targetHints", required = false) String targetHints
    ) {
        log.info("detect_ui_elements 占位执行, imagePath={}, detectorType={}", imagePath, detectorType);
        /**
         * 统一返回空元素集合和原始请求参数，便于前后链路在未实现阶段完成联调。
         */
        return success(Map.of(
                "elements", List.of(),
                "imagePath", imagePath,
                "detectorType", detectorType,
                "targetHints", targetHints,
                "message", "NOT_IMPLEMENTED"
        ));
    }
}
