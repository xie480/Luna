package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptSnapshotBridgeService;
import org.yilena.luna.prompt.governance.entity.PromptRuntimeSnapshotRefEntity;
import org.yilena.luna.prompt.governance.mapper.PromptRuntimeSnapshotRefMapper;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PromptSnapshotBridgeServiceImpl implements PromptSnapshotBridgeService {

    private final PromptRuntimeSnapshotRefMapper promptRuntimeSnapshotRefMapper;

    @Override
    public Map<String, Object> buildSnapshotPayload(PromptResolveResult resolveResult, String policyId) {
        String effectivePolicyId = firstNonBlank(policyId, resolveResult == null ? null : resolveResult.getPolicyId());
        if (resolveResult == null) {
            return emptyPayload(effectivePolicyId);
        }
        List<ResolvedPromptItem> matchedItems = resolveResult.getMatchedItems() == null ? List.of() : resolveResult.getMatchedItems();
        List<Map<String, Object>> refs = matchedItems.stream()
                .map(this::toPromptRefRow)
                .toList();
        Map<String, List<Map<String, Object>>> slotMapping = new LinkedHashMap<>();
        Map<String, List<ResolvedPromptItem>> rawSlotMapping = resolveResult.getSlotMapping() == null ? Map.of() : resolveResult.getSlotMapping();
        for (Map.Entry<String, List<ResolvedPromptItem>> entry : rawSlotMapping.entrySet()) {
            String slot = safe(entry.getKey());
            if (slot.isBlank()) {
                continue;
            }
            List<Map<String, Object>> items = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream().map(this::toPromptRefRow).toList();
            slotMapping.put(slot, items);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyId", safe(effectivePolicyId));
        payload.put("assemblerVersion", "assembler.v1");
        payload.put("promptRefs", refs);
        payload.put("slotMapping", slotMapping);
        return payload;
    }

    @Override
    public void persistSnapshotRefs(String sessionId,
                                    Long roundId,
                                    Long nodeId,
                                    String snapshotId,
                                    Map<String, Object> snapshotPayload) {
        if (snapshotPayload == null || snapshotPayload.isEmpty()) {
            return;
        }
        Object refsRaw = snapshotPayload.get("promptRefs");
        if (!(refsRaw instanceof List<?> refs) || refs.isEmpty()) {
            return;
        }
        String policyId = safe(snapshotPayload.get("policyId"));
        String assemblerVersion = safe(snapshotPayload.get("assemblerVersion"));
        try {
            for (Object ref : refs) {
                if (!(ref instanceof Map<?, ?> row)) {
                    continue;
                }
                PromptRuntimeSnapshotRefEntity entity = PromptRuntimeSnapshotRefEntity.builder()
                        .sessionId(sessionId)
                        .roundId(roundId)
                        .nodeId(nodeId)
                        .snapshotId(snapshotId)
                        .promptItemId(toLong(row.get("itemId")))
                        .promptItemVersionId(toLong(row.get("versionId")))
                        .promptKey(safe(row.get("key")))
                        .promptVersionNo(safe(row.get("version")))
                        .policyId(policyId)
                        .assemblerVersion(firstNonBlank(safe(row.get("assemblerVersion")), assemblerVersion))
                        .runtimeSlot(safe(row.get("runtimeSlot")))
                        .matchReason(safe(row.get("matchReason")))
                        .resolvedValue(safe(row.get("value")))
                        .build();
                promptRuntimeSnapshotRefMapper.insert(entity);
            }
        } catch (Exception ignore) {
            // noop, must not block main flow
        }
    }

    @Override
    public void persistSnapshotRefs(String sessionId,
                                    Long roundId,
                                    Long nodeId,
                                    String snapshotId,
                                    String policyId,
                                    PromptResolveResult resolveResult) {
        Map<String, Object> snapshotPayload = buildSnapshotPayload(resolveResult, policyId);
        persistSnapshotRefs(sessionId, roundId, nodeId, snapshotId, snapshotPayload);
    }

    private Map<String, Object> emptyPayload(String policyId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyId", safe(policyId));
        payload.put("assemblerVersion", "assembler.v1");
        payload.put("promptRefs", List.of());
        payload.put("slotMapping", Map.of());
        return payload;
    }

    private Map<String, Object> toPromptRefRow(ResolvedPromptItem item) {
        if (item == null) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", item.getItemId());
        row.put("versionId", item.getVersionId());
        row.put("key", safe(item.getKey()));
        row.put("version", safe(item.getVersion()));
        row.put("assemblerVersion", safe(item.getAssemblerVersion()));
        row.put("runtimeSlot", safe(item.getRuntimeSlot()));
        row.put("matchReason", safe(item.getMatchReason()));
        row.put("reason", safe(item.getMatchReason()));
        row.put("category", safe(item.getCategory()));
        row.put("value", safe(item.getValue()));
        return row;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback == null ? "" : fallback;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }
}
