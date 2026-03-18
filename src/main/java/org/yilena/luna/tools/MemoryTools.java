package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
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

    @LunaState(value = LunaStateConstant.VALUE_MEMORY, status = LunaStateConstant.STATUS_MEMORY)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_MEMORY, type = LogType.TOOL_CALL, content = "管理长期记忆")
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
