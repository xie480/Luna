package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
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

    @LunaState(value = "Luna 正在查阅系统日志...", status = "LOG")
    @Tool("""
    【系统日志(LunaLog) 管理工具】
    用于查询、插入或删除系统日志。
    
    参数说明:
    - action: 必填。可选值: "INSERT", "QUERY", "DELETE"
    - logType: INSERT/QUERY 时选填。枚举: LUNA_OUTPUT, TOOL_CALL, ERROR, SELF_UPDATE, SYSTEM_EVENT, API_CALL
    - module: INSERT/QUERY 时选填。
    - content: INSERT 时选填。
    - startTime: QUERY/DELETE 时选填。格式: yyyy-MM-dd HH:mm:ss
    - endTime: QUERY 时选填。格式: yyyy-MM-dd HH:mm:ss
    - limit: QUERY 时选填，默认 10。
    - id: DELETE 时必填 (除非使用 beforeTime)。
    - beforeTime: DELETE 时选填，删除此时间之前的日志。
    """)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL)
    public String manageLog(String action, String logType, String module, String content, String startTime, String endTime, Integer limit, Long id, String beforeTime) {
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
                if (logType != null) wrapper.eq(LunaLog::getLogType, LogType.valueOf(logType.toUpperCase()));
                if (module != null) wrapper.eq(LunaLog::getModule, module);
                if (startTime != null) wrapper.ge(LunaLog::getCreateAt, LocalDateTime.parse(startTime, DATE_TIME_FORMATTER));
                if (endTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(endTime, DATE_TIME_FORMATTER));
                wrapper.orderByDesc(LunaLog::getCreateAt);
                wrapper.last("LIMIT " + (limit != null ? limit : 10));
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
