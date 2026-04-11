package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
/**
 * 工具状态模型，负责记录最近一次工具调用的输入、状态、结果引用与历史轨迹，
 * 为工具语义总结、恢复和调试链路提供上下文依据。
 */
public class ToolState {
    /**
     * 最近一次调用的工具名称。
     */
    String lastToolName;
    /**
     * 最近一次工具调用输入内容。
     */
    String lastToolInput;
    /**
     * 最近一次工具调用状态。
     */
    String lastToolStatus;
    /**
     * 最近一次工具原始结果引用。
     */
    String lastToolRawResultRef;
    /**
     * 最近一次工具原始载荷引用。
     */
    String lastToolRawPayloadRef;
    /**
     * 最近一次工具原始结果正文。
     */
    String lastToolRawResult;
    /**
     * 最近一次工具原始结果摘要哈希。
     */
    String lastToolRawResultDigest;
    /**
     * 最近一次工具原始结果预览文本。
     */
    String lastToolRawResultPreview;
    /**
     * 最近一次工具调用的语义总结。
     */
    String lastToolSemanticSummary;
    /**
     * 工具调用历史引用列表。
     */
    List<String> toolCallHistoryRefs;
}
