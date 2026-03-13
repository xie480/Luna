package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.entity.ScheduleTask;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.enums.TaskStatus;
import org.yilena.luna.enums.TaskType;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.mapper.ScheduleTaskMapper;
import org.yilena.luna.mapper.UserPreferenceMapper;
import org.yilena.luna.service.KnowledgeBaseService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Luna 智能体工具集
 * 包含联网搜索、知识库写入、记忆管理、日程管理等工具
 * 供大模型通过 Function Calling 主动调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LunaTools {

    private final KnowledgeBaseService knowledgeBaseService;
    private final UserPreferenceMapper userPreferenceMapper;
    private final ScheduleTaskMapper scheduleTaskMapper;
    private final MemoryMapper memoryMapper;

    /**
     * 联网搜索工具
     * 目前为模拟实现，实际生产环境应对接 Google Serper / Bing Search API 等
     */
    @Tool("当你需要回答的问题超出了你的知识范围，或者需要获取实时信息（如新闻、天气、股价）时，调用此工具进行联网搜索。")
    public String searchWeb(String query) {
        log.info("Luna 正在执行联网搜索，关键词: {}", query);
        
        // TODO: 对接真实的搜索引擎 API
        if (query.contains("天气")) {
            return "【搜索结果】: 今天天气晴朗，气温 25 度，适合外出。";
        } else if (query.contains("新闻")) {
            return "【搜索结果】: 最新科技新闻显示，AI Agent 技术正在快速发展。";
        }
        
        return "【搜索结果】: 关于 \"" + query + "\" 的网络搜索暂未返回具体内容，请尝试更换关键词或告知用户无法获取实时信息。";
    }

    /**
     * 写入知识库工具
     * 用于将有价值的信息（如搜索结果、用户的重要笔记）永久存入向量数据库
     */
    @Tool("当你从联网搜索中获取了有价值的信息，或者用户提供了重要的文档内容时，调用此工具将其保存到知识库中，以便未来检索。")
    public String saveToKnowledgeBase(String title, String content) {
        log.info("Luna 正在写入知识库，标题: {}", title);
        try {
            // 假设 SourceType 中有 TEXT 或类似枚举，如果没有请根据实际情况调整
            knowledgeBaseService.addKnowledge(title, content, SourceType.values()[0], "Luna-Auto-Learning");
            return "成功将内容写入知识库。";
        } catch (Exception e) {
            log.error("写入知识库失败", e);
            return "写入知识库失败: " + e.getMessage();
        }
    }

    /**
     * 写入用户偏好工具
     * 用于记录用户的个人喜好、称呼、习惯等
     */
    @Tool("当用户明确表达了某种偏好（如'叫我主人'、'我喜欢简洁的回答'）时，调用此工具记录用户偏好。")
    public String saveUserPreference(String key, String value) {
        log.info("Luna 正在记录用户偏好，Key: {}, Value: {}", key, value);
        try {
            UserPreference existing = userPreferenceMapper.selectOne(
                    new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getPrefKey, key)
            );

            if (existing != null) {
                existing.setPrefValue(value);
                userPreferenceMapper.updateById(existing);
            } else {
                UserPreference pref = UserPreference.builder()
                        .prefKey(key)
                        .prefValue(value)
                        .build();
                userPreferenceMapper.insert(pref);
            }
            return "已成功记录用户偏好。";
        } catch (Exception e) {
            return "记录用户偏好失败: " + e.getMessage();
        }
    }

    /**
     * 添加日程任务工具
     */
    @Tool("当用户要求你提醒某事或安排日程时，调用此工具添加任务。时间格式必须为 'yyyy-MM-dd HH:mm:ss'。")
    public String addScheduleTask(String content, String timeString) {
        log.info("Luna 正在添加日程，内容: {}, 时间: {}", content, timeString);
        try {
            LocalDateTime triggerTime;
            try {
                triggerTime = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                // 如果解析失败，尝试使用当前时间延后一小时作为默认值
                triggerTime = LocalDateTime.now().plusHours(1);
                log.warn("时间格式解析失败，使用默认时间: {}", triggerTime);
            }

            ScheduleTask task = ScheduleTask.builder()
                    .content(content)
                    .triggerTime(triggerTime)
                    .status(TaskStatus.values()[0]) // 默认取第一个枚举值 (通常是 0-待处理)
                    .taskType(TaskType.REMINDER)    // 对应 REMINDER(提醒)
                    .build();
            
            scheduleTaskMapper.insert(task);
            return "已添加日程任务，预定时间: " + triggerTime.toString();
        } catch (Exception e) {
            return "添加日程失败: " + e.getMessage();
        }
    }

    /**
     * 写入长期记忆工具
     * 用于存储关于用户的关键事实或长期需要记住的信息
     */
    @Tool("当你需要记住关于用户的一个关键事实（非偏好类，例如用户的生日、家庭成员、职业背景）时，调用此工具。")
    public String saveMemory(String content, String category) {
        log.info("Luna 正在写入长期记忆，类别: {}, 内容: {}", category, content);
        try {
            Memory memory = Memory.builder()
                    .content(content)
                    .category(category)
                    .build();
            
            memoryMapper.insert(memory);
            return "已将信息写入长期记忆。";
        } catch (Exception e) {
            return "写入记忆失败: " + e.getMessage();
        }
    }
}
