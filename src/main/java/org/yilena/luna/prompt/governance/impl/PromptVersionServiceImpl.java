package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PromptVersionServiceImpl implements PromptVersionService {

    private final PromptItemMapper promptItemMapper;
    private final PromptItemVersionMapper promptItemVersionMapper;

    @Override
    public List<PromptItemVersionEntity> listVersions(String key) {
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
    @Transactional(rollbackFor = Exception.class)
    public void activateVersion(Long versionId) {
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
                        .set(PromptItemVersionEntity::getIsActive, false));
        promptItemVersionMapper.update(null,
                new LambdaUpdateWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getId, versionId)
                        .set(PromptItemVersionEntity::getIsActive, true)
                        .set(PromptItemVersionEntity::getStatus, "active"));
        promptItemMapper.update(null,
                new LambdaUpdateWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getId, version.getPromptItemId())
                        .set(PromptItemEntity::getCurrentVersionId, versionId)
                        .set(PromptItemEntity::getStatus, "active"));
    }

    @Override
    public void rollbackToVersion(String key, Long versionId) {
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
}
