package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.DateTimeConstant;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.ScheduleTask;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.ScheduleActionEnum;
import org.yilena.luna.enums.ScheduleUpdateModeEnum;
import org.yilena.luna.enums.TaskStatus;
import org.yilena.luna.enums.TaskType;
import org.yilena.luna.mapper.ScheduleTaskMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;

@Component
public class ScheduleTools extends BaseTool {

    private final ScheduleTaskMapper scheduleTaskMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMMSS);

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
            ScheduleActionEnum actionEnum = ScheduleActionEnum.fromCode(action);
            return switch (actionEnum) {
                case INSERT -> insertTask(content, triggerTime, status, taskType);
                case QUERY -> queryTask(status);
                case UPDATE -> updateTask(id, mode, content, triggerTime, status, taskType);
                case DELETE -> deleteTask(id, hardDelete);
            };
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("No enum constant")) {
                return error("枚举值无效。TaskStatus 可选: " + Arrays.toString(TaskStatus.values())
                        + ", TaskType 可选: " + Arrays.toString(TaskType.values()));
            }
            return error("参数错误: " + e.getMessage());
        } catch (DateTimeParseException e) {
            return error("时间格式错误，请使用 '" + DateTimeConstant.FORMAT_YYYYMMDDHHMMSS + "'。输入值: " + triggerTime);
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }

    private String insertTask(String content, String triggerTime, String status, String taskType) {
        if (content == null || triggerTime == null || status == null || taskType == null) {
            return error("INSERT 必须提供 content, triggerTime, status, taskType");
        }
        ScheduleTask task = ScheduleTask.builder()
                .content(content)
                .triggerTime(LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER))
                .status(TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)))
                .taskType(TaskType.valueOf(taskType.toUpperCase(Locale.ROOT)))
                .build();
        scheduleTaskMapper.insert(task);
        return success(scheduleTaskMapper.selectById(task.getId()));
    }

    private String queryTask(String status) {
        LambdaQueryWrapper<ScheduleTask> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(ScheduleTask::getStatus, TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)));
        }
        return success(scheduleTaskMapper.selectList(wrapper));
    }

    private String updateTask(Long id,
                              String mode,
                              String content,
                              String triggerTime,
                              String status,
                              String taskType) {
        if (id == null || mode == null) {
            return error("UPDATE 必须提供 id 和 mode");
        }
        ScheduleTask existing = scheduleTaskMapper.selectById(id);
        if (existing == null) {
            return error("未找到 id=" + id + " 的记录");
        }

        ScheduleUpdateModeEnum updateMode = ScheduleUpdateModeEnum.fromCode(mode);
        switch (updateMode) {
            case PUT -> {
                existing.setContent(content);
                existing.setTriggerTime(triggerTime != null ? LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER) : null);
                existing.setStatus(status != null ? TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)) : null);
                existing.setTaskType(taskType != null ? TaskType.valueOf(taskType.toUpperCase(Locale.ROOT)) : null);
            }
            case PATCH -> {
                if (content != null) {
                    existing.setContent(content);
                }
                if (triggerTime != null) {
                    existing.setTriggerTime(LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER));
                }
                if (status != null) {
                    existing.setStatus(TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)));
                }
                if (taskType != null) {
                    existing.setTaskType(TaskType.valueOf(taskType.toUpperCase(Locale.ROOT)));
                }
            }
        }
        scheduleTaskMapper.updateById(existing);
        return success(scheduleTaskMapper.selectById(id));
    }

    private String deleteTask(Long id, Boolean hardDelete) {
        if (id == null) {
            return error("DELETE 必须提供 id");
        }
        if (Boolean.TRUE.equals(hardDelete)) {
            SqlRunner.db().delete("DELETE FROM schedule_task WHERE id = {0}", id);
            return success("已执行物理删除 id=" + id);
        }
        scheduleTaskMapper.deleteById(id);
        return success("已执行逻辑删除 id=" + id);
    }
}
