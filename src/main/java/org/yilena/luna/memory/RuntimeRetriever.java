package org.yilena.luna.memory;

import java.util.Map;

/**
 * 运行态检索接口，负责聚合当前会话最近消息、计划推进和工具执行等即时运行信息，
 * 为上下文编译与编排决策提供基础事实输入。
 */
public interface RuntimeRetriever {
    Map<String, Object> retrieve(String sessionId);
}
