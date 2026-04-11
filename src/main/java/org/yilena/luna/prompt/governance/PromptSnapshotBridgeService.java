package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptResolveResult;

import java.util.Map;

/**
 * 提示词快照桥接服务接口，负责把提示词解析结果转换为快照载荷并持久化引用关系，
 * 让运行时快照能够追踪本轮实际生效的提示词版本。
 */
public interface PromptSnapshotBridgeService {
    Map<String, Object> buildSnapshotPayload(PromptResolveResult resolveResult, String policyId);

    void persistSnapshotRefs(String sessionId,
                             Long roundId,
                             Long nodeId,
                             String snapshotId,
                             Map<String, Object> snapshotPayload);

    void persistSnapshotRefs(String sessionId,
                             Long roundId,
                             Long nodeId,
                             String snapshotId,
                             String policyId,
                             PromptResolveResult resolveResult);
}
