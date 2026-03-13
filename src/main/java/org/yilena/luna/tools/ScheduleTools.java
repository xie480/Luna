package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
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

    @LunaState(value = "Luna 正在安排日程任务...", status = "SCHEDULE")
    @Tool("""
    【日程任务(ScheduleTask) CRUD 工具】
    目标实体类定义 (Schema):
    - id: Long (自动生成, 插入时不填)
    - content: String (必填, 任务内容)
    - triggerTime: String (必填, 格式: yyyy-MM-dd HH:mm:ss)
    - status: String (必填, 枚举: PENDING, COMPLETED, CANCELLED, EXPIRED)
    - taskType: String (必填, 枚举: REMINDER, ACTION, TODO)
    - createdAt: DateTime (自动生成)
    - updatedAt: DateTime (自动生成)
    - deleted: Integer (自动生成, 逻辑删除标记)

    参数说明:
    - action: 必填。可选值: "INSERT", "UPDATE", "DELETE", "QUERY"
    - id: UPDATE 和 DELETE 时必填。
    - mode: UPDATE 时必填。可选值: "PATCH", "PUT"
    - hardDelete: DELETE 时选填。true 为物理删除，false 为逻辑删除(默认)。
    - content, triggerTime, status, taskType: 根据 action 和 mode 提供。
    """)
    @LunaLogRecord(module = "tool", action = "manage_schedule", type = LogType.TOOL_CALL)
    public String manageScheduleTask(String action, Long id, String mode, String content, String triggerTime, String status, String taskType, Boolean hardDelete) {
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
            if (e.getMessage().contains("No enum constant")) {
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
