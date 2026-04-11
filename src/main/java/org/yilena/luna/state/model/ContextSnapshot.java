package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
/**
 * 上下文快照模型，负责承载某次上下文落盘后的基础标识与快照载荷，
 * 用于回放、审计和恢复链路读取指定快照内容。
 */
public class ContextSnapshot {
    /**
     * 快照唯一标识。
     */
    String snapshotId;
    /**
     * 所属会话标识。
     */
    String sessionId;
    /**
     * 关联的计划标识。
     */
    Long planId;
    /**
     * 关联的节点标识。
     */
    Long nodeId;
    /**
     * 快照中保存的上下文载荷内容。
     */
    Map<String, Object> payload;
}
