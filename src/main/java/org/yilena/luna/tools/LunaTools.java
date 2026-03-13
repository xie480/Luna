package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.entity.ScheduleTask;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.enums.MemoryType;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.enums.TaskStatus;
import org.yilena.luna.enums.TaskType;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.mapper.ScheduleTaskMapper;
import org.yilena.luna.mapper.UserPreferenceMapper;
import org.yilena.luna.service.KnowledgeBaseService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Luna 智能体工具集
 * 提供标准化的 CRUD 操作，供大模型通过 Function Calling 主动调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LunaTools {

    private final KnowledgeBaseService knowledgeBaseService;
    private final UserPreferenceMapper userPreferenceMapper;
    private final ScheduleTaskMapper scheduleTaskMapper;
    private final MemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 联网搜索工具
     */
    @Tool("当你需要回答的问题超出了你的知识范围，或者需要获取实时信息（如新闻、天气、股价）时，调用此工具进行联网搜索。返回格式为 JSON。")
    public String searchWeb(String query) {
        log.info("Luna 正在执行联网搜索，关键词: {}", query);
        // TODO: 对接真实的搜索引擎 API
        String result;
        if (query.contains("天气")) {
            result = "【搜索结果】: 今天天气晴朗，气温 25 度，适合外出。";
        } else if (query.contains("新闻")) {
            result = "【搜索结果】: 最新科技新闻显示，AI Agent 技术正在快速发展。";
        } else {
            result = "【搜索结果】: 关于 \"" + query + "\" 的网络搜索暂未返回具体内容，请尝试更换关键词或告知用户无法获取实时信息。";
        }
        return success(result);
    }

    @Tool("""
    【用户偏好(UserPreference) CRUD 工具】
    目标实体类定义 (Schema):
    - id: Long (自动生成, 插入时不填)
    - prefKey: String (必填, 偏好键, 如 "theme", "nickname")
    - prefValue: String (必填, 偏好值)
    - description: String (选填, 描述/备注)
    - createdAt: DateTime (自动生成)
    - updatedAt: DateTime (自动生成)
    - deleted: Integer (自动生成, 逻辑删除标记)

    业务流程约束: 当偏好变更时，必须先调用 QUERY 查出旧偏好，再决定是 UPDATE 还是 DELETE 后重新 INSERT。

    参数说明:
    - action: 必填。可选值: "INSERT", "UPDATE", "DELETE", "QUERY"
    - id: UPDATE 和 DELETE 时必填。
    - mode: UPDATE 时必填。可选值: "PATCH" (部分更新，忽略空值), "PUT" (全量替换，空值将覆盖原值)
    - hardDelete: DELETE 时选填。true 为物理删除，false 为逻辑删除(默认)。
    - prefKey, prefValue, description: 根据 action 和 mode 提供。

    返回格式示例:
    - 成功: {"status":"success", "data": {"id":1, "prefKey":"theme", "prefValue":"dark", ...}}
    - 失败: {"status":"error", "message":"INSERT 操作必须提供 prefKey 和 prefValue"}
    """)
    public String manageUserPreference(String action, Long id, String mode, String prefKey, String prefValue, String description, Boolean hardDelete) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (prefKey == null || prefValue == null) return error("INSERT 必须提供 prefKey 和 prefValue");
                UserPreference pref = UserPreference.builder().prefKey(prefKey).prefValue(prefValue).description(description).build();
                userPreferenceMapper.insert(pref);
                return success(userPreferenceMapper.selectById(pref.getId()));
            } else if ("QUERY".equalsIgnoreCase(action)) {
                LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
                if (prefKey != null) wrapper.eq(UserPreference::getPrefKey, prefKey);
                return success(userPreferenceMapper.selectList(wrapper));
            } else if ("UPDATE".equalsIgnoreCase(action)) {
                if (id == null || mode == null) return error("UPDATE 必须提供 id 和 mode(PATCH/PUT)");
                UserPreference existing = userPreferenceMapper.selectById(id);
                if (existing == null) return error("未找到 id=" + id + " 的记录");

                if ("PUT".equalsIgnoreCase(mode)) {
                    existing.setPrefKey(prefKey);
                    existing.setPrefValue(prefValue);
                    existing.setDescription(description);
                } else if ("PATCH".equalsIgnoreCase(mode)) {
                    if (prefKey != null) existing.setPrefKey(prefKey);
                    if (prefValue != null) existing.setPrefValue(prefValue);
                    if (description != null) existing.setDescription(description);
                } else {
                    return error("未知的 mode: " + mode);
                }
                userPreferenceMapper.updateById(existing);
                return success(userPreferenceMapper.selectById(id));
            } else if ("DELETE".equalsIgnoreCase(action)) {
                if (id == null) return error("DELETE 必须提供 id");
                if (Boolean.TRUE.equals(hardDelete)) {
                    SqlRunner.db().delete("DELETE FROM user_preference WHERE id = {0}", id);
                    return success("已执行物理删除 id=" + id);
                } else {
                    userPreferenceMapper.deleteById(id);
                    return success("已执行逻辑删除 id=" + id);
                }
            }
            return error("未知的 action: " + action);
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }

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

    @Tool("""
    【长期记忆(Memory) CRUD 工具】
    目标实体类定义 (Schema):
    - id: Long (自动生成, 插入时不填)
    - sessionId: String (选填, 会话ID或日期标识, 如 "2023:10:27")
    - memoryType: String (必填, 枚举: FACT, PREFERENCE, SUMMARY, REFLECTION)
    - content: String (必填, 记忆内容)
    - weight: Integer (选填, 权重, 默认 1)
    - createdAt: DateTime (自动生成)
    - updatedAt: DateTime (自动生成)

    参数说明:
    - action: 必填。可选值: "INSERT", "UPDATE", "DELETE", "QUERY"
    - id: UPDATE 和 DELETE 时必填。
    - mode: UPDATE 时必填。可选值: "PATCH", "PUT"
    - hardDelete: DELETE 时选填。true 为物理删除，false 为逻辑删除(默认)。
    - sessionId, memoryType, content, weight: 根据 action 和 mode 提供。
    """)
    public String manageMemory(String action, Long id, String mode, String sessionId, String memoryType, String content, Integer weight, Boolean hardDelete) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (memoryType == null || content == null) return error("INSERT 必须提供 memoryType 和 content");
                Memory memory = Memory.builder()
                        .sessionId(sessionId)
                        .memoryType(MemoryType.valueOf(memoryType.toUpperCase()))
                        .content(content)
                        .weight(weight != null ? weight : 1)
                        .build();
                memoryMapper.insert(memory);
                return success(memoryMapper.selectById(memory.getId()));
            } else if ("QUERY".equalsIgnoreCase(action)) {
                LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<>();
                if (memoryType != null) wrapper.eq(Memory::getMemoryType, MemoryType.valueOf(memoryType.toUpperCase()));
                if (sessionId != null) wrapper.eq(Memory::getSessionId, sessionId);
                return success(memoryMapper.selectList(wrapper));
            } else if ("UPDATE".equalsIgnoreCase(action)) {
                if (id == null || mode == null) return error("UPDATE 必须提供 id 和 mode");
                Memory existing = memoryMapper.selectById(id);
                if (existing == null) return error("未找到 id=" + id + " 的记录");

                if ("PUT".equalsIgnoreCase(mode)) {
                    existing.setSessionId(sessionId);
                    existing.setMemoryType(memoryType != null ? MemoryType.valueOf(memoryType.toUpperCase()) : null);
                    existing.setContent(content);
                    existing.setWeight(weight);
                } else if ("PATCH".equalsIgnoreCase(mode)) {
                    if (sessionId != null) existing.setSessionId(sessionId);
                    if (memoryType != null) existing.setMemoryType(MemoryType.valueOf(memoryType.toUpperCase()));
                    if (content != null) existing.setContent(content);
                    if (weight != null) existing.setWeight(weight);
                }
                memoryMapper.updateById(existing);
                return success(memoryMapper.selectById(id));
            } else if ("DELETE".equalsIgnoreCase(action)) {
                if (id == null) return error("DELETE 必须提供 id");
                if (Boolean.TRUE.equals(hardDelete)) {
                    SqlRunner.db().delete("DELETE FROM luna_memory WHERE id = {0}", id);
                    return success("已执行物理删除 id=" + id);
                } else {
                    memoryMapper.deleteById(id);
                    return success("已执行逻辑删除 id=" + id);
                }
            }
            return error("未知的 action: " + action);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("No enum constant")) {
                return error("枚举值无效。MemoryType 可选值: " + Arrays.toString(MemoryType.values()));
            }
            return error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }

    @Tool("""
    【知识库(KnowledgeBase) CRUD 工具】
    目标实体类定义 (Schema):
    - id: Long (自动生成)
    - title: String (必填, 标题)
    - content: String (必填, 内容)
    - sourceType: String (必填, 来源类型, 如 TEXT, WEB, FILE)
    - sourcePath: String (选填, 来源路径)

    参数说明:
    - action: 必填。可选值: "INSERT", "QUERY" (注: 知识库涉及向量化，暂不支持直接 UPDATE/DELETE，请通过重新 INSERT 覆盖)
    - title, content, sourceType, sourcePath: INSERT 时提供。
    - query: QUERY 时提供的搜索词。
    """)
    public String manageKnowledgeBase(String action, String title, String content, String sourceType, String sourcePath, String query) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (title == null || content == null || sourceType == null) {
                    return error("INSERT 必须提供 title, content 和 sourceType");
                }
                SourceType st;
                try {
                    st = SourceType.valueOf(sourceType.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 明确提示错误，而不是默默使用默认值
                    return error("无效的 sourceType: " + sourceType + "。可选值: " + Arrays.toString(SourceType.values()));
                }
                knowledgeBaseService.addKnowledge(title, content, st, sourcePath);
                return success("知识库写入成功");
            } else if ("QUERY".equalsIgnoreCase(action)) {
                if (query == null) return error("QUERY 必须提供 query");
                return success(knowledgeBaseService.searchKnowledge(query, 5));
            }
            return error("未知的 action: " + action + "，知识库暂仅支持 INSERT 和 QUERY");
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }

    // --- 辅助方法 ---

    private String success(Object data) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "success");
            map.put("data", data);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"JSON序列化失败\"}";
        }
    }

    private String error(String message) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "error");
            map.put("message", message);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"" + message + "\"}";
        }
    }
}
