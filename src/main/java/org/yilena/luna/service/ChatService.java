package org.yilena.luna.service;

import org.springframework.http.ResponseEntity;
import org.yilena.luna.entity.ChatRequest;

import java.util.List;

/**
 * 对话服务接口，负责定义聊天主流程、生命周期控制和历史查询能力。
 */
public interface ChatService {

    /**
     * 执行一轮聊天请求。
     */
    ResponseEntity<Object> chat(ChatRequest chatRequest);

    /**
     * 执行系统启动准备流程。
     */
    ResponseEntity<Object> startup();

    /**
     * 执行系统关闭清理流程。
     */
    void shutdown();

    /**
     * 查询指定年月下存在历史记录的日期列表。
     */
    List<String> getHistoryDate(String yearMonth);

    /**
     * 查询指定日期的历史对话内容。
     */
    List<String> getHistory(String yearMonthDay);
}
