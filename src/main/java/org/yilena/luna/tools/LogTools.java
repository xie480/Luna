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
import org.yilena.luna.enums.ToolActionEnum;
import org.yilena.luna.service.LunaLogService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
/**
 * 日志工具类，负责提供日志的新增、条件查询和清理能力，便于智能体回溯系统运行轨迹。
 */
public class LogTools extends BaseTool {

    /**
     * 日志时间字符串解析格式，用于统一处理查询条件中的时间参数。
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 日志查询默认返回条数，避免单次工具调用读取过多数据。
     */
    private static final int DEFAULT_QUERY_LIMIT = 50;

    /**
     * 日志服务，用于执行日志持久化、查询和删除操作。
     */
    private final LunaLogService lunaLogService;

    public LogTools(ObjectMapper objectMapper, LunaLogService lunaLogService) {
        super(objectMapper);
        this.lunaLogService = lunaLogService;
    }

    @LunaState(value = LunaStateConstant.VALUE_LOG, status = LunaStateConstant.STATUS_LOG)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "管理系统日志")
    /**
     * 统一处理日志管理请求，根据动作类型执行日志写入、检索或清理流程。
     */
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
            /**
             * 先解析工具动作，确保日志操作只在受支持的增删查范围内执行。
             */
            ToolActionEnum actionEnum = ToolActionEnum.getByCode(action).orElse(null);
            /**
             * 插入流程将外部参数组装为日志实体并立即持久化，便于后续审计和排障。
             */
            if (actionEnum == ToolActionEnum.INSERT) {
                LunaLog log = LunaLog.builder()
                        .logType(logType != null ? LogType.valueOf(logType.toUpperCase()) : LogType.SYSTEM_EVENT)
                        .module(module)
                        .content(content)
                        .createAt(LocalDateTime.now())
                        .build();
                lunaLogService.save(log);
                return success("日志插入成功，ID: " + log.getId());
            }
            /**
             * 查询流程按主键、类型、模块、内容和时间窗口动态拼接条件，返回最近日志结果。
             */
            if (actionEnum == ToolActionEnum.QUERY) {
                LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>();
                if (id != null) wrapper.eq(LunaLog::getId, id);
                if (logType != null) wrapper.eq(LunaLog::getLogType, LogType.valueOf(logType.toUpperCase()));
                if (module != null) wrapper.eq(LunaLog::getModule, module);
                if (content != null) wrapper.like(LunaLog::getContent, content);
                if (startTime != null) wrapper.ge(LunaLog::getCreateAt, LocalDateTime.parse(startTime, DATE_TIME_FORMATTER));
                if (endTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(endTime, DATE_TIME_FORMATTER));
                if (beforeTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(beforeTime, DATE_TIME_FORMATTER));
                wrapper.orderByDesc(LunaLog::getCreateAt);
                wrapper.last("LIMIT " + (limit != null ? limit : DEFAULT_QUERY_LIMIT));
                return success(lunaLogService.list(wrapper));
            }
            /**
             * 删除流程支持按单条主键删除，或按时间阈值批量清理历史日志。
             */
            if (actionEnum == ToolActionEnum.DELETE) {
                if (id != null) {
                    lunaLogService.removeById(id);
                    return success("已删除日志 ID: " + id);
                }
                if (beforeTime != null) {
                    LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>();
                    wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(beforeTime, DATE_TIME_FORMATTER));
                    lunaLogService.remove(wrapper);
                    return success("已清理 " + beforeTime + " 之前的日志");
                }
                return error("DELETE requires id or beforeTime");
            }
            return error("未知的 action: " + action);
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }
}
