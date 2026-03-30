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
public class DesktopTools extends BaseTool {

    public DesktopTools(ObjectMapper objectMapper) { // 定义方法签名
        super(objectMapper); // 执行语句逻辑
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_DESKTOP, status = LunaStateConstant.STATUS_DESKTOP) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "抓取桌面截图") // 声明注解
    public String captureDesktopScreenshot( // 定义方法签名
            @RequestParam(value = "monitorIndex", required = false) Integer monitorIndex, // 声明注解
            @RequestParam(value = "region", required = false) String region // 声明注解
    ) { // 开始新的代码块
        log.info("capture_desktop_screenshot 占位执行, monitorIndex={}, region={}", monitorIndex, region); // 执行赋值操作
        return success(Map.of( // 返回处理结果
                "imagePath", "", // 执行当前逻辑
                "width", 0, // 执行当前逻辑
                "height", 0, // 执行当前逻辑
                "monitorIndex", monitorIndex, // 执行当前逻辑
                "region", region, // 执行当前逻辑
                "message", "NOT_IMPLEMENTED" // 执行当前逻辑
        )); // 执行语句逻辑
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_DESKTOP, status = LunaStateConstant.STATUS_DESKTOP) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "识别UI元素") // 声明注解
    public String detectUiElements( // 定义方法签名
            @RequestParam(value = "imagePath", required = false) String imagePath, // 声明注解
            @RequestParam(value = "detectorType", required = false) String detectorType, // 声明注解
            @RequestParam(value = "targetHints", required = false) String targetHints // 声明注解
    ) { // 开始新的代码块
        log.info("detect_ui_elements 占位执行, imagePath={}, detectorType={}", imagePath, detectorType); // 执行赋值操作
        return success(Map.of( // 返回处理结果
                "elements", List.of(), // 执行当前逻辑
                "imagePath", imagePath, // 执行当前逻辑
                "detectorType", detectorType, // 执行当前逻辑
                "targetHints", targetHints, // 执行当前逻辑
                "message", "NOT_IMPLEMENTED" // 执行当前逻辑
        )); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
