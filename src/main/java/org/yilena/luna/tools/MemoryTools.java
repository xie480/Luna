package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.MemoryType;
import org.yilena.luna.mapper.MemoryMapper;

import java.util.Arrays;

@Component
public class MemoryTools extends BaseTool {

    private final MemoryMapper memoryMapper;

    public MemoryTools(ObjectMapper objectMapper, MemoryMapper memoryMapper) {
        super(objectMapper);
        this.memoryMapper = memoryMapper;
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
    @LunaLogRecord(module = "tool", action = "manage_memory", type = LogType.TOOL_CALL)
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
}
