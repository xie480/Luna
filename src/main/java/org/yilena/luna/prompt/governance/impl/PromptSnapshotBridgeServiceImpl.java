package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptSnapshotBridgeService;
import org.yilena.luna.prompt.governance.entity.PromptRuntimeSnapshotRefEntity;
import org.yilena.luna.prompt.governance.mapper.PromptRuntimeSnapshotRefMapper;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PromptSnapshotBridgeServiceImpl implements PromptSnapshotBridgeService {

    private final PromptRuntimeSnapshotRefMapper promptRuntimeSnapshotRefMapper;

    @Override
    public Map<String, Object> buildSnapshotPayload(PromptResolveResult resolveResult, String policyId) {
        if (resolveResult == null || resolveResult.getMatchedItems() == null) {
            return Map.of(
                    "policyId", policyId == null ? "" : policyId,
                    "promptRefs", List.of()
            );
        }
        List<Map<String, Object>> refs = resolveResult.getMatchedItems().stream()
                .map(item -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("key", item.getKey() == null ? "" : item.getKey());
                    row.put("version", item.getVersion() == null ? "" : item.getVersion());
                    row.put("assemblerVersion", item.getAssemblerVersion() == null ? "" : item.getAssemblerVersion());
                    row.put("runtimeSlot", item.getRuntimeSlot() == null ? "" : item.getRuntimeSlot());
                    row.put("reason", item.getMatchReason() == null ? "" : item.getMatchReason());
                    return row;
                })
                .toList();
        return Map.of(
                "policyId", policyId == null ? "" : policyId,
                "promptRefs", refs
        );
    }

    @Override
    public void persistSnapshotRefs(String sessionId,
                                    Long roundId,
                                    Long nodeId,
                                    String snapshotId,
                                    String policyId,
                                    PromptResolveResult resolveResult) {
        if (resolveResult == null || resolveResult.getMatchedItems() == null || resolveResult.getMatchedItems().isEmpty()) {
            return;
        }
        try {
            for (ResolvedPromptItem item : resolveResult.getMatchedItems()) {
                PromptRuntimeSnapshotRefEntity row = PromptRuntimeSnapshotRefEntity.builder()
                        .sessionId(sessionId)
                        .roundId(roundId)
                        .nodeId(nodeId)
                        .snapshotId(snapshotId)
                        .promptItemId(item.getItemId())
                        .promptItemVersionId(item.getVersionId())
                        .promptKey(item.getKey())
                        .promptVersionNo(item.getVersion())
                        .policyId(policyId)
                        .assemblerVersion(item.getAssemblerVersion())
                        .runtimeSlot(item.getRuntimeSlot())
                        .matchReason(item.getMatchReason())
                        .resolvedValue(item.getValue())
                        .build();
                promptRuntimeSnapshotRefMapper.insert(row);
            }
        } catch (Exception ignore) {
            // noop, must not block main flow
        }
    }
}
