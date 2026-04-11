package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
/**
 * 日程工具类，负责统一处理日程任务的新增、查询、更新和删除，供对话侧管理提醒与待办事项。
 */
public class ScheduleTools extends BaseTool {

    /**
     * 日程任务数据访问对象，用于持久化和查询任务记录。
     */
    private final ScheduleTaskMapper scheduleTaskMapper;

    /**
     * 日程触发时间的统一解析格式。
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMMSS);

    public ScheduleTools(ObjectMapper objectMapper, ScheduleTaskMapper scheduleTaskMapper) {
        super(objectMapper);
        this.scheduleTaskMapper = scheduleTaskMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_SCHEDULE, status = LunaStateConstant.STATUS_SCHEDULE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_SCHEDULE, type = LogType.TOOL_CALL, content = "管理日程任务")
    /**
     * 统一处理日程任务管理请求，根据动作类型分发到新增、查询、更新或删除流程。
     */
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
            /**
             * 先解析动作枚举，再路由到对应子流程，保证入口层的调度逻辑清晰统一。
             */
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

    /**
     * 新增日程任务，要求一次性提供任务内容、触发时间、状态和任务类型。
     */
    private String insertTask(String content, String triggerTime, String status, String taskType) {
        if (content == null || triggerTime == null || status == null || taskType == null) {
            return error("INSERT 必须提供 content, triggerTime, status, taskType");
        }
        /**
         * 构建完整任务实体后直接入库，确保新任务具备可执行的最小信息集合。
         */
        ScheduleTask task = ScheduleTask.builder()
                .content(content)
                .triggerTime(LocalDateTime.parse(triggerTime, DATE_TIME_FORMATTER))
                .status(TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)))
                .taskType(TaskType.valueOf(taskType.toUpperCase(Locale.ROOT)))
                .build();
        scheduleTaskMapper.insert(task);
        return success(scheduleTaskMapper.selectById(task.getId()));
    }

    /**
     * 查询日程任务，支持按状态过滤当前任务列表。
     */
    private String queryTask(String status) {
        LambdaQueryWrapper<ScheduleTask> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(ScheduleTask::getStatus, TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)));
        }
        return success(scheduleTaskMapper.selectList(wrapper));
    }

    /**
     * 更新日程任务，支持全量覆盖和局部修改两种模式。
     */
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

        /**
         * 按更新模式决定是整体替换任务内容，还是只修改传入字段，避免误覆盖未变更数据。
         */
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

    /**
     * 删除日程任务，按参数决定执行逻辑删除还是物理删除。
     */
    private String deleteTask(Long id, Boolean hardDelete) {
        if (id == null) {
            return error("DELETE 必须提供 id");
        }
        /**
         * 物理删除用于彻底移除任务，逻辑删除用于保留数据痕迹但隐藏任务。
         */
        if (Boolean.TRUE.equals(hardDelete)) {
            scheduleTaskMapper.hardDeleteById(id);
            return success("已执行物理删除 id=" + id);
        }
        scheduleTaskMapper.deleteById(id);
        return success("已执行逻辑删除 id=" + id);
    }
}
