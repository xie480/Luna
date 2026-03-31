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
public class PreferenceTools extends BaseTool {

    public PreferenceTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_PREFERENCE, status = LunaStateConstant.STATUS_PREFERENCE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_PREFERENCE, type = LogType.TOOL_CALL, content = "legacy preference tool retired")
    public String manageUserPreference(
            @RequestParam("action") String action,
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "prefKey", required = false) String prefKey,
            @RequestParam(value = "prefValue", required = false) String prefValue,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "hardDelete", required = false) Boolean hardDelete) {
        return error("legacy user_preference tool retired, use manage_memory with RELATION/SEMANTIC instead");
    }
}
