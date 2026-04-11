package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 检索状态模型，负责记录当前轮次的重构意图、检索计划和已选证据，
 * 为后续重用检索结果和解释证据选择提供状态依据。
 */
public class RetrievalState {
    /**
     * 最近一次重构后的检索意图。
     */
    String reconstructedIntent;
    /**
     * 当前激活的查询语句集合。
     */
    List<String> activeQueries;
    /**
     * 当前轮次采用的检索计划配置。
     */
    Map<String, Object> retrievalPlan;
    /**
     * 已选中的证据引用集合。
     */
    List<String> selectedEvidenceRefs;
    /**
     * 重排阶段产出的摘要说明。
     */
    String rerankSummary;
}
