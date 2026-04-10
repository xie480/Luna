package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptSnapshotBridgeService;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.entity.PromptRuntimeSnapshotRefEntity;
import org.yilena.luna.prompt.governance.mapper.PromptRuntimeSnapshotRefMapper;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;
import org.yilena.luna.prompt.governance.support.PromptSectionAssemblerSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 快照桥接服务实现，负责把 Prompt 解析结果转换成快照引用并持久化到运行时快照关联表。
 */
@Service
@RequiredArgsConstructor
public class PromptSnapshotBridgeServiceImpl implements PromptSnapshotBridgeService {

    private final PromptRuntimeSnapshotRefMapper promptRuntimeSnapshotRefMapper;
    private final PromptPolicyService promptPolicyService;
    @Value("${prompt.governance.assembler-version:assembler.v1}")
    private String assemblerVersion = "assembler.v1";

    @Override
    public Map<String, Object> buildSnapshotPayload(PromptResolveResult resolveResult, String policyId) {
        /**
         * 先把解析结果转成快照载荷，统一输出策略信息、Prompt 引用列表和槽位映射。
         */
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
        payload.put("policyKey", safe(effectivePolicyId));
        payload.put("assemblerVersion", resolveAssemblerVersion());
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
        /**
         * 快照持久化阶段会逐条把 Prompt 引用转换成数据库实体，建立快照与 Prompt 版本的追踪关系。
         */
        if (snapshotPayload == null || snapshotPayload.isEmpty()) {
            return;
        }
        Object refsRaw = snapshotPayload.get("promptRefs");
        if (!(refsRaw instanceof List<?> refs) || refs.isEmpty()) {
            return;
        }
        String policyKey = firstNonBlank(safe(snapshotPayload.get("policyKey")), safe(snapshotPayload.get("policyId")));
        Long policyId = resolvePolicyDbId(policyKey);
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
                        .promptItemId(firstNonNullLong(toLong(row.get("promptItemId")), toLong(row.get("itemId"))))
                        .promptItemVersionId(firstNonNullLong(toLong(row.get("promptItemVersionId")), toLong(row.get("versionId"))))
                        .promptKey(firstNonBlank(safe(row.get("promptKey")), safe(row.get("key"))))
                        .promptVersionNo(firstNonBlank(safe(row.get("promptVersion")), safe(row.get("version"))))
                        .policyKey(policyKey)
                        .policyId(policyId)
                        .assemblerVersion(firstNonBlank(safe(row.get("assemblerVersion")), assemblerVersion))
                        .runtimeSlot(safe(row.get("runtimeSlot")))
                        .matchReason(safe(row.get("matchReason")))
                        .policyApplied(readBoolean(row.get("policyApplied")))
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
        payload.put("policyKey", safe(policyId));
        payload.put("assemblerVersion", resolveAssemblerVersion());
        payload.put("promptRefs", List.of());
        payload.put("slotMapping", Map.of());
        return payload;
    }

    private Map<String, Object> toPromptRefRow(ResolvedPromptItem item) {
        /**
         * 统一把解析后的 Prompt 条目转换为快照引用行，便于后续持久化和快照回放。
         */
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
        row.put("policyApplied", item.isPolicyApplied());
        row.put("category", safe(item.getCategory()));
        row.put("value", safe(item.getValue()));
        return PromptSectionAssemblerSupport.withPromptRefAliases(row);
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

    private Long firstNonNullLong(Long first, Long fallback) {
        return first != null ? first : fallback;
    }

    private Long resolvePolicyDbId(String policyKey) {
        if (policyKey == null || policyKey.isBlank()) {
            return null;
        }
        try {
            PromptPolicyEntity policy = promptPolicyService.getByPolicyId(policyKey);
            return policy == null ? null : policy.getId();
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private String resolveAssemblerVersion() {
        if (assemblerVersion == null || assemblerVersion.isBlank()) {
            return "assembler.v1";
        }
        return assemblerVersion;
    }
}
