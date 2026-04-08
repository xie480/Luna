package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptResolveResult;

import java.util.Map;

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
