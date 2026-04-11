package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;

@Component
/**
 * 偏好工具兼容类，负责拦截已废弃的旧版偏好工具调用，并引导请求迁移到记忆工具。
 */
public class PreferenceTools extends BaseTool {

    public PreferenceTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_PREFERENCE, status = LunaStateConstant.STATUS_PREFERENCE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_PREFERENCE, type = LogType.TOOL_CALL, content = "legacy preference tool retired")
    /**
     * 兼容旧版用户偏好工具入口，当前仅返回迁移提示，避免继续写入已废弃链路。
     */
    public String manageUserPreference(
            @RequestParam("action") String action,
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "prefKey", required = false) String prefKey,
            @RequestParam(value = "prefValue", required = false) String prefValue,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "hardDelete", required = false) Boolean hardDelete) {
        /**
         * 统一返回废弃提示，明确要求调用方改用关系域语义记忆能力管理偏好数据。
         */
        return error("legacy user_preference tool retired, use manage_memory with RELATION/SEMANTIC instead");
    }
}
