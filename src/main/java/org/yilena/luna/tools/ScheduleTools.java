package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.ScheduleTask;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.TaskStatus;
import org.yilena.luna.enums.TaskType;
import org.yilena.luna.mapper.ScheduleTaskMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

@Component
public class ScheduleTools extends BaseTool {

    private final ScheduleTaskMapper scheduleTaskMapper;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ScheduleTools(ObjectMapper objectMapper, ScheduleTaskMapper scheduleTaskMapper) {
        super(objectMapper);
        this.scheduleTaskMapper = scheduleTaskMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_SCHEDULE, status = LunaStateConstant.STATUS_SCHEDULE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_SCHEDULE, type = LogType.TOOL_CALL, content = "管理日程任务")
    public String manageScheduleTask(
            @RequestParam("action") String action,
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "triggerTime", required = false) String triggerTime,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "taskType", required = false) String taskType,
            @RequestParam(value = "hardDelete", required = false) Boolean hardDelete) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (content == null || triggerTime == null || status == null || taskType == null) {
                    return error("INSERT 必须提供 content, triggerTime, status, taskType");
                }
                ScheduleTask task = ScheduleTask.builder()
                        .content(content)
                        .triggerTime(LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER))
                        .status(TaskStatus.valueOf(status.toUpperCase()))
                        .taskType(TaskType.valueOf(taskType.toUpperCase()))
                        .build();
                scheduleTaskMapper.insert(task);
                return success(scheduleTaskMapper.selectById(task.getId()));
            } else if ("QUERY".equalsIgnoreCase(action)) {
                LambdaQueryWrapper<ScheduleTask> wrapper = new LambdaQueryWrapper<>();
                if (status != null) wrapper.eq(ScheduleTask::getStatus, TaskStatus.valueOf(status.toUpperCase()));
                return success(scheduleTaskMapper.selectList(wrapper));
            } else if ("UPDATE".equalsIgnoreCase(action)) {
                if (id == null || mode == null) return error("UPDATE 必须提供 id 和 mode");
                ScheduleTask existing = scheduleTaskMapper.selectById(id);
                if (existing == null) return error("未找到 id=" + id + " 的记录");

                if ("PUT".equalsIgnoreCase(mode)) {
                    existing.setContent(content);
                    existing.setTriggerTime(triggerTime != null ? LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER) : null);
                    existing.setStatus(status != null ? TaskStatus.valueOf(status.toUpperCase()) : null);
                    existing.setTaskType(taskType != null ? TaskType.valueOf(taskType.toUpperCase()) : null);
                } else if ("PATCH".equalsIgnoreCase(mode)) {
                    if (content != null) existing.setContent(content);
                    if (triggerTime != null) existing.setTriggerTime(LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER));
                    if (status != null) existing.setStatus(TaskStatus.valueOf(status.toUpperCase()));
                    if (taskType != null) existing.setTaskType(TaskType.valueOf(taskType.toUpperCase()));
                } else {
                    return error("未知的 mode: " + mode + "，仅支持 PUT 或 PATCH");
                }
                scheduleTaskMapper.updateById(existing);
                return success(scheduleTaskMapper.selectById(id));
            } else if ("DELETE".equalsIgnoreCase(action)) {
                if (id == null) return error("DELETE 必须提供 id");
                if (Boolean.TRUE.equals(hardDelete)) {
                    SqlRunner.db().delete("DELETE FROM schedule_task WHERE id = {0}", id);
                    return success("已执行物理删除 id=" + id);
                } else {
                    scheduleTaskMapper.deleteById(id);
                    return success("已执行逻辑删除 id=" + id);
                }
            }
            return error("未知的 action: " + action);
        } catch (IllegalArgumentException e) {
            // 专门捕获枚举解析错误，提示正确的值
            if (e.getMessage() != null && e.getMessage().contains("No enum constant")) {
                return error("枚举值无效。TaskStatus 可选值: " + Arrays.toString(TaskStatus.values()) +
                        ", TaskType 可选值: " + Arrays.toString(TaskType.values()));
            }
            return error("参数错误: " + e.getMessage());
        } catch (DateTimeParseException e) {
            return error("时间格式错误，请使用 'yyyy-MM-dd HH:mm:ss'。输入值为: " + triggerTime);
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }
}
