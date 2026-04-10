package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prompt 版本服务实现，负责管理版本列表、激活、回滚、草稿保存和版本差异比较。
 */
@Service
@RequiredArgsConstructor
public class PromptVersionServiceImpl implements PromptVersionService {

    private final PromptItemMapper promptItemMapper;
    private final PromptItemVersionMapper promptItemVersionMapper;
    private final PromptCategoryService promptCategoryService;

    @Override
    public List<PromptItemVersionEntity> listVersions(String key) {
        /**
         * 先定位 Prompt 条目，再按创建时间倒序读取其全部版本历史。
         */
        if (key == null || key.isBlank()) {
            return List.of();
        }
        PromptItemEntity item = promptItemMapper.selectOne(
                new LambdaQueryWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getPromptKey, key.trim())
                        .last("limit 1")
        );
        if (item == null) {
            return List.of();
        }
        return promptItemVersionMapper.selectList(
                new LambdaQueryWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getPromptItemId, item.getId())
                        .orderByDesc(PromptItemVersionEntity::getCreatedAt)
        );
    }

    @Override
    public PromptItemVersionEntity getVersionDetail(Long versionId) {
        if (versionId == null || versionId <= 0) {
            return null;
        }
        return promptItemVersionMapper.selectById(versionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activateVersion(Long versionId) {
        /**
         * 激活版本时先归档旧活动版本，再切换目标版本和条目主记录状态。
         */
        if (versionId == null || versionId <= 0) {
            throw new IllegalArgumentException("versionId is required");
        }
        PromptItemVersionEntity version = promptItemVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("version not found");
        }
        promptItemVersionMapper.update(null,
                new LambdaUpdateWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getPromptItemId, version.getPromptItemId())
                        .eq(PromptItemVersionEntity::getIsActive, true)
                        .set(PromptItemVersionEntity::getIsActive, false)
                        .set(PromptItemVersionEntity::getStatus, "archived"));
        promptItemVersionMapper.update(null,
                new LambdaUpdateWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getId, versionId)
                        .set(PromptItemVersionEntity::getIsActive, true)
                        .set(PromptItemVersionEntity::getStatus, "active"));
        promptItemMapper.update(null,
                new LambdaUpdateWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getId, version.getPromptItemId())
                        .set(PromptItemEntity::getCurrentVersionId, versionId)
                        .set(PromptItemEntity::getEnabled, true)
                        .set(PromptItemEntity::getStatus, "enabled"));
    }

    @Override
    public void rollbackToVersion(String key, Long versionId) {
        /**
         * 回滚本质上是一次归属校验后的版本激活，确保目标版本属于当前 Prompt。
         */
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (versionId == null || versionId <= 0) {
            throw new IllegalArgumentException("versionId is required");
        }
        PromptItemEntity item = promptItemMapper.selectOne(
                new LambdaQueryWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getPromptKey, key.trim())
                        .last("limit 1")
        );
        if (item == null) {
            throw new IllegalArgumentException("prompt not found");
        }
        PromptItemVersionEntity version = promptItemVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("version not found");
        }
        if (!item.getId().equals(version.getPromptItemId())) {
            throw new IllegalArgumentException("version does not belong to key");
        }
        activateVersion(versionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptItemVersionEntity saveDraft(String key, PromptUpsertRequest request) {
        /**
         * 草稿保存会继承最新版本的未修改字段，生成一个未激活的草稿版本供后续审核或编辑。
         */
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        PromptItemEntity item = promptItemMapper.selectOne(
                new LambdaQueryWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getPromptKey, key.trim())
                        .last("limit 1")
        );
        if (item == null) {
            throw new IllegalArgumentException("prompt not found");
        }
        PromptItemVersionEntity latest = latestVersion(item.getId());
        PromptItemVersionEntity draft = PromptItemVersionEntity.builder()
                .promptItemId(item.getId())
                .versionNo(nextVersionNo(item.getId()))
                .versionLabel(request == null || request.getVersionLabel() == null || request.getVersionLabel().isBlank()
                        ? "draft"
                        : request.getVersionLabel())
                .promptValue(request != null && request.getValue() != null ? request.getValue() : (latest == null ? "" : safe(latest.getPromptValue())))
                .templateVariables(request != null && request.getTemplateVariables() != null ? request.getTemplateVariables() : (latest == null ? List.of() : safeList(latest.getTemplateVariables())))
                .matchKeywords(request != null && request.getMatchKeywords() != null ? request.getMatchKeywords() : (latest == null ? List.of() : safeList(latest.getMatchKeywords())))
                .matchScope(request != null && request.getMatchScope() != null ? request.getMatchScope() : (latest == null ? Map.of() : safeMap(latest.getMatchScope())))
                .editPolicy(request != null && request.getEditPolicy() != null ? request.getEditPolicy() : (latest == null ? defaultEditPolicy() : safeMap(latest.getEditPolicy())))
                .status("draft")
                .changeNote(request == null || request.getChangeNote() == null ? "draft_save" : request.getChangeNote())
                .isActive(false)
                .build();
        promptItemVersionMapper.insert(draft);
        return draft;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveVersion(Long versionId) {
        /**
         * 归档版本时需要保护执行类 Prompt 的当前版本，避免运行时关键 Prompt 被直接下线。
         */
        if (versionId == null || versionId <= 0) {
            throw new IllegalArgumentException("versionId is required");
        }
        PromptItemVersionEntity version = promptItemVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("version not found");
        }
        PromptItemEntity item = promptItemMapper.selectById(version.getPromptItemId());
        boolean archivingCurrentVersion = item != null
                && item.getCurrentVersionId() != null
                && item.getCurrentVersionId().equals(versionId);
        if (archivingCurrentVersion && isExecutionPrompt(item)) {
            throw new IllegalArgumentException("execution prompt current version cannot be archived");
        }
        promptItemVersionMapper.update(null,
                new LambdaUpdateWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getId, versionId)
                        .set(PromptItemVersionEntity::getStatus, "archived")
                        .set(PromptItemVersionEntity::getIsActive, false));
        if (item != null && item.getCurrentVersionId() != null && item.getCurrentVersionId().equals(versionId)) {
            promptItemMapper.update(null,
                    new LambdaUpdateWrapper<PromptItemEntity>()
                            .eq(PromptItemEntity::getId, item.getId())
                            .set(PromptItemEntity::getCurrentVersionId, null)
                            .set(PromptItemEntity::getEnabled, false)
                            .set(PromptItemEntity::getStatus, "disabled"));
        }
    }

    @Override
    public Map<String, Object> diff(Long leftVersionId, Long rightVersionId) {
        /**
         * 差异比较按行生成简化 diff，供治理界面快速查看两个版本的文本变更。
         */
        if (leftVersionId == null || rightVersionId == null) {
            throw new IllegalArgumentException("version ids are required");
        }
        PromptItemVersionEntity left = promptItemVersionMapper.selectById(leftVersionId);
        PromptItemVersionEntity right = promptItemVersionMapper.selectById(rightVersionId);
        if (left == null || right == null) {
            throw new IllegalArgumentException("version not found");
        }
        if (!left.getPromptItemId().equals(right.getPromptItemId())) {
            throw new IllegalArgumentException("versions are not from same prompt item");
        }
        List<String> lines = new ArrayList<>();
        String[] leftLines = safe(left.getPromptValue()).split("\\R", -1);
        String[] rightLines = safe(right.getPromptValue()).split("\\R", -1);
        int max = Math.max(leftLines.length, rightLines.length);
        for (int i = 0; i < max; i++) {
            String l = i < leftLines.length ? leftLines[i] : "";
            String r = i < rightLines.length ? rightLines[i] : "";
            if (l.equals(r)) {
                continue;
            }
            if (!l.isEmpty()) {
                lines.add("- " + l);
            }
            if (!r.isEmpty()) {
                lines.add("+ " + r);
            }
        }
        return Map.of(
                "leftVersionId", leftVersionId,
                "rightVersionId", rightVersionId,
                "leftVersionNo", safe(left.getVersionNo()),
                "rightVersionNo", safe(right.getVersionNo()),
                "changed", !lines.isEmpty(),
                "diffLines", lines
        );
    }

    private PromptItemVersionEntity latestVersion(Long itemId) {
        List<PromptItemVersionEntity> versions = promptItemVersionMapper.selectList(
                new LambdaQueryWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getPromptItemId, itemId)
                        .orderByDesc(PromptItemVersionEntity::getCreatedAt)
                        .last("limit 1")
        );
        return versions.isEmpty() ? null : versions.get(0);
    }

    private String nextVersionNo(Long itemId) {
        PromptItemVersionEntity latest = latestVersion(itemId);
        if (latest == null || latest.getVersionNo() == null || latest.getVersionNo().isBlank()) {
            return "1.0.0";
        }
        String[] parts = latest.getVersionNo().split("\\.");
        if (parts.length != 3) {
            return "1.0.0";
        }
        try {
            int patch = Integer.parseInt(parts[2]) + 1;
            return parts[0] + "." + parts[1] + "." + patch;
        } catch (Exception ignore) {
            return "1.0.0";
        }
    }

    private Map<String, Object> defaultEditPolicy() {
        return Map.of("create", true, "update", true, "delete", true);
    }

    private boolean isExecutionPrompt(PromptItemEntity item) {
        if (item == null) {
            return false;
        }
        if (Boolean.TRUE.equals(item.getHasTemplateVariables())) {
            return true;
        }
        return promptCategoryService.isExecutionCategory(resolveItemCategory(item));
    }

    private String resolveItemCategory(PromptItemEntity item) {
        if (item == null) {
            return "";
        }
        if (item.getCategoryKey() != null && !item.getCategoryKey().isBlank()) {
            return item.getCategoryKey();
        }
        return item.getCategory() == null ? "" : item.getCategory();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }
}
