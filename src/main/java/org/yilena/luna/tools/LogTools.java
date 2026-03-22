package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.LunaLog;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.service.LunaLogService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class LogTools extends BaseTool {

    private final LunaLogService lunaLogService;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public LogTools(ObjectMapper objectMapper, LunaLogService lunaLogService) {
        super(objectMapper);
        this.lunaLogService = lunaLogService;
    }

    @LunaState(value = LunaStateConstant.VALUE_LOG, status = LunaStateConstant.STATUS_LOG)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "管理系统日志")
    public String manageLog(
            @RequestParam("action") String action,
            @RequestParam(value = "logType", required = false) String logType,
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "beforeTime", required = false) String beforeTime) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                LunaLog log = LunaLog.builder()
                        .logType(logType != null ? LogType.valueOf(logType.toUpperCase()) : LogType.SYSTEM_EVENT)
                        .module(module)
                        .content(content)
                        .createAt(LocalDateTime.now())
                        .build();
                lunaLogService.save(log);
                return success("日志插入成功，ID: " + log.getId());
            } else if ("QUERY".equalsIgnoreCase(action)) {
                LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>();
                if (id != null) wrapper.eq(LunaLog::getId, id);
                if (logType != null) wrapper.eq(LunaLog::getLogType, LogType.valueOf(logType.toUpperCase()));
                if (module != null) wrapper.eq(LunaLog::getModule, module);
                if (content != null) wrapper.like(LunaLog::getContent, content);
                if (startTime != null) wrapper.ge(LunaLog::getCreateAt, LocalDateTime.parse(startTime, DATE_TIME_FORMATTER));
                if (endTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(endTime, DATE_TIME_FORMATTER));
                if (beforeTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(beforeTime, DATE_TIME_FORMATTER));
                wrapper.orderByDesc(LunaLog::getCreateAt);
                wrapper.last("LIMIT " + (limit != null ? limit : 50));
                return success(lunaLogService.list(wrapper));
            } else if ("DELETE".equalsIgnoreCase(action)) {
                if (id != null) {
                    lunaLogService.removeById(id);
                    return success("已删除日志 ID: " + id);
                } else if (beforeTime != null) {
                    LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>();
                    wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(beforeTime, DATE_TIME_FORMATTER));
                    lunaLogService.remove(wrapper);
                    return success("已清理 " + beforeTime + " 之前的日志");
                }
                return error("DELETE 操作必须提供 id 或 beforeTime");
            }
            return error("未知的 action: " + action);
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }
}
