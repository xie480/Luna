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
/**
 * LogTools ??
 */
public class LogTools extends BaseTool {

    private final LunaLogService lunaLogService; // 声明成员字段
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // 定义方法签名

    public LogTools(ObjectMapper objectMapper, LunaLogService lunaLogService) { // 定义方法签名
        super(objectMapper); // 执行语句逻辑
        this.lunaLogService = lunaLogService; // 执行赋值操作
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_LOG, status = LunaStateConstant.STATUS_LOG) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "管理系统日志") // 声明注解
    public String manageLog( // 定义方法签名
            @RequestParam("action") String action, // 声明注解
            @RequestParam(value = "logType", required = false) String logType, // 声明注解
            @RequestParam(value = "module", required = false) String module, // 声明注解
            @RequestParam(value = "content", required = false) String content, // 声明注解
            @RequestParam(value = "startTime", required = false) String startTime, // 声明注解
            @RequestParam(value = "endTime", required = false) String endTime, // 声明注解
            @RequestParam(value = "limit", required = false) Integer limit, // 声明注解
            @RequestParam(value = "id", required = false) Long id, // 声明注解
            @RequestParam(value = "beforeTime", required = false) String beforeTime) { // 声明注解
        try { // 尝试执行核心逻辑
            if ("INSERT".equalsIgnoreCase(action)) { // 进行条件判断
                LunaLog log = LunaLog.builder() // 执行赋值操作
                        .logType(logType != null ? LogType.valueOf(logType.toUpperCase()) : LogType.SYSTEM_EVENT) // 执行赋值操作
                        .module(module) // 执行当前逻辑
                        .content(content) // 执行当前逻辑
                        .createAt(LocalDateTime.now()) // 执行当前逻辑
                        .build(); // 执行语句逻辑
                lunaLogService.save(log); // 执行语句逻辑
                return success("日志插入成功，ID: " + log.getId()); // 返回处理结果
            } else if ("QUERY".equalsIgnoreCase(action)) { // 切换到分支逻辑
                LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>(); // 执行赋值操作
                if (id != null) wrapper.eq(LunaLog::getId, id); // 进行条件判断
                if (logType != null) wrapper.eq(LunaLog::getLogType, LogType.valueOf(logType.toUpperCase())); // 进行条件判断
                if (module != null) wrapper.eq(LunaLog::getModule, module); // 进行条件判断
                if (content != null) wrapper.like(LunaLog::getContent, content); // 进行条件判断
                if (startTime != null) wrapper.ge(LunaLog::getCreateAt, LocalDateTime.parse(startTime, DATE_TIME_FORMATTER)); // 进行条件判断
                if (endTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(endTime, DATE_TIME_FORMATTER)); // 进行条件判断
                if (beforeTime != null) wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(beforeTime, DATE_TIME_FORMATTER)); // 进行条件判断
                wrapper.orderByDesc(LunaLog::getCreateAt); // 执行语句逻辑
                wrapper.last("LIMIT " + (limit != null ? limit : 50)); // 执行赋值操作
                return success(lunaLogService.list(wrapper)); // 返回处理结果
            } else if ("DELETE".equalsIgnoreCase(action)) { // 切换到分支逻辑
                if (id != null) { // 进行条件判断
                    lunaLogService.removeById(id); // 执行语句逻辑
                    return success("已删除日志 ID: " + id); // 返回处理结果
                } else if (beforeTime != null) { // 切换到分支逻辑
                    LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>(); // 执行赋值操作
                    wrapper.le(LunaLog::getCreateAt, LocalDateTime.parse(beforeTime, DATE_TIME_FORMATTER)); // 执行语句逻辑
                    lunaLogService.remove(wrapper); // 执行语句逻辑
                    return success("已清理 " + beforeTime + " 之前的日志"); // 返回处理结果
                } // 结束当前代码块
                return error("DELETE 操作必须提供 id 或 beforeTime"); // 返回处理结果
            } // 结束当前代码块
            return error("未知的 action: " + action); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return error("操作异常: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
