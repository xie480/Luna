package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.McpClientAdapter;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.entity.McpPromptCatalog;
import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpResourceCatalog;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpToolCatalog;
import org.yilena.luna.entity.McpToolDescriptor;
import org.yilena.luna.mapper.CapabilityMapper;
import org.yilena.luna.mapper.McpPromptCatalogMapper;
import org.yilena.luna.mapper.McpResourceCatalogMapper;
import org.yilena.luna.mapper.McpServerRegistryMapper;
import org.yilena.luna.mapper.McpToolCatalogMapper;
import org.yilena.luna.service.CapabilityCatalogSyncService;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityCatalogSyncServiceImpl implements CapabilityCatalogSyncService {

    private static final long SYNC_MIN_INTERVAL_MS = 15_000L;

    private final McpClientAdapter mcpClientAdapter;
    private final McpServerRegistryMapper serverRegistryMapper;
    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final CapabilityMapper capabilityMapper;
    private final LlmClientUtil llmClientUtil;

    private volatile long lastSyncEpochMs = 0L;

    @Override
    public void syncFromServers() {
        long now = System.currentTimeMillis();
        if (now - lastSyncEpochMs < SYNC_MIN_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            long guardNow = System.currentTimeMillis();
            if (guardNow - lastSyncEpochMs < SYNC_MIN_INTERVAL_MS) {
                return;
            }
            List<McpServerRegistry> servers = serverRegistryMapper.selectList(
                    new LambdaQueryWrapper<McpServerRegistry>()
                            .eq(McpServerRegistry::getEnabled, true)
            );
            if (servers == null || servers.isEmpty()) {
                syncOneServer(McpConstant.LOCAL_SERVER_CODE, null);
            } else {
                for (McpServerRegistry server : servers) {
                    syncOneServer(server.getServerCode(), server);
                }
            }
            refreshCapabilityRegistry();
            lastSyncEpochMs = System.currentTimeMillis();
        }
    }

    private void syncOneServer(String serverCode, McpServerRegistry registry) {
        String target = normalizeServerCode(serverCode);
        try {
            List<McpToolDescriptor> tools = mcpClientAdapter.listTools(target);
            upsertToolCatalog(target, tools);
            List<McpPromptDescriptor> prompts = mcpClientAdapter.listPrompts(target);
            upsertPromptCatalog(target, prompts);
            List<McpResourceDescriptor> resources = mcpClientAdapter.listResources(target);
            upsertResourceCatalog(target, resources);
            touchServerSyncTime(target, registry);
        } catch (Exception e) {
            log.warn("catalog sync failed for serverCode={}, err={}", target, e.getMessage());
        }
    }

    private void upsertToolCatalog(String serverCode, List<McpToolDescriptor> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        for (McpToolDescriptor descriptor : tools) {
            String toolName = descriptor.getToolName();
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            McpToolCatalog row = toolCatalogMapper.selectOne(
                    new LambdaQueryWrapper<McpToolCatalog>()
                            .eq(McpToolCatalog::getServerCode, serverCode)
                            .eq(McpToolCatalog::getToolName, toolName)
                            .last("LIMIT 1")
            );
            if (row == null) {
                row = new McpToolCatalog();
                row.setServerCode(serverCode);
                row.setToolName(toolName);
            }
            row.setTitle(normalizeTitle(descriptor.getTitle(), toolName));
            row.setDescription(descriptor.getDescription());
            row.setInputSchema(descriptor.getInputSchema());
            row.setOutputSchema(descriptor.getOutputSchema());
            row.setRequiresApproval(Boolean.TRUE.equals(descriptor.getRequiresApproval()));
            row.setSensitivity(normalizeSensitivity(descriptor.getSensitivity()));
            row.setVersion(descriptor.getVersion() == null || descriptor.getVersion().isBlank() ? "1" : descriptor.getVersion().trim());
            applyToolEmbedding(row);
            row.setEnabled(true);
            row.setSyncedAt(LocalDateTime.now());
            if (row.getId() == null) {
                toolCatalogMapper.insert(row);
            } else {
                toolCatalogMapper.updateById(row);
            }
        }
    }

    private void upsertPromptCatalog(String serverCode, List<McpPromptDescriptor> prompts) {
        if (prompts == null || prompts.isEmpty()) {
            return;
        }
        for (McpPromptDescriptor descriptor : prompts) {
            String promptName = descriptor.getPromptName();
            if (promptName == null || promptName.isBlank()) {
                continue;
            }
            McpPromptCatalog row = promptCatalogMapper.selectOne(
                    new LambdaQueryWrapper<McpPromptCatalog>()
                            .eq(McpPromptCatalog::getServerCode, serverCode)
                            .eq(McpPromptCatalog::getPromptName, promptName)
                            .last("LIMIT 1")
            );
            if (row == null) {
                row = new McpPromptCatalog();
                row.setServerCode(serverCode);
                row.setPromptName(promptName);
            }
            row.setTitle(normalizeTitle(descriptor.getTitle(), promptName));
            row.setDescription(descriptor.getDescription());
            row.setArgumentsSchema(descriptor.getArgumentsSchema());
            row.setVersion(descriptor.getVersion() == null || descriptor.getVersion().isBlank() ? "1" : descriptor.getVersion().trim());
            applyPromptEmbedding(row);
            row.setEnabled(true);
            row.setSyncedAt(LocalDateTime.now());
            if (row.getId() == null) {
                promptCatalogMapper.insert(row);
            } else {
                promptCatalogMapper.updateById(row);
            }
        }
    }

    private void upsertResourceCatalog(String serverCode, List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        for (McpResourceDescriptor descriptor : resources) {
            String resourceUri = descriptor.getResourceUri();
            if (resourceUri == null || resourceUri.isBlank()) {
                continue;
            }
            McpResourceCatalog row = resourceCatalogMapper.selectOne(
                    new LambdaQueryWrapper<McpResourceCatalog>()
                            .eq(McpResourceCatalog::getServerCode, serverCode)
                            .eq(McpResourceCatalog::getResourceUri, resourceUri)
                            .last("LIMIT 1")
            );
            if (row == null) {
                row = new McpResourceCatalog();
                row.setServerCode(serverCode);
                row.setResourceUri(resourceUri);
            }
            row.setName(normalizeTitle(descriptor.getName(), resourceUri));
            row.setDescription(descriptor.getDescription());
            row.setMimeType(descriptor.getMimeType());
            row.setAnnotations(descriptor.getAnnotations());
            applyResourceEmbedding(row);
            row.setEnabled(true);
            row.setSyncedAt(LocalDateTime.now());
            if (row.getId() == null) {
                resourceCatalogMapper.insert(row);
            } else {
                resourceCatalogMapper.updateById(row);
            }
        }
    }

    private void touchServerSyncTime(String serverCode, McpServerRegistry registry) {
        try {
            McpServerRegistry row = registry;
            if (row == null) {
                row = serverRegistryMapper.selectOne(
                        new LambdaQueryWrapper<McpServerRegistry>()
                                .eq(McpServerRegistry::getServerCode, serverCode)
                                .last("LIMIT 1")
                );
            }
            if (row == null) {
                return;
            }
            row.setLastSyncAt(LocalDateTime.now());
            row.setHealthStatus("UP");
            serverRegistryMapper.updateById(row);
        } catch (Exception e) {
            log.debug("update server sync time failed, serverCode={}, err={}", serverCode, e.getMessage());
        }
    }

    private void refreshCapabilityRegistry() {
        capabilityMapper.syncToolsIntoRegistry();
        capabilityMapper.syncPromptsIntoRegistry();
        capabilityMapper.syncResourcesIntoRegistry();
        capabilityMapper.syncWorkflowsIntoRegistry();
        capabilityMapper.syncTaskStrategiesIntoRegistry();
        capabilityMapper.syncRelationalStrategiesIntoRegistry();
    }

    private String normalizeServerCode(String serverCode) {
        if (serverCode == null || serverCode.isBlank()) {
            return McpConstant.LOCAL_SERVER_CODE;
        }
        return serverCode.trim();
    }

    private String normalizeTitle(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeSensitivity(String value) {
        if (value == null || value.isBlank()) {
            return "LOW";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH" -> normalized;
            default -> "LOW";
        };
    }

    private void applyToolEmbedding(McpToolCatalog row) {
        if (row == null) {
            return;
        }
        String text = joinText(row.getToolName(), row.getTitle(), row.getDescription());
        String embedding = generateEmbedding(text);
        if (embedding != null) {
            row.setEmbedding(embedding);
        }
    }

    private void applyPromptEmbedding(McpPromptCatalog row) {
        if (row == null) {
            return;
        }
        String text = joinText(row.getPromptName(), row.getTitle(), row.getDescription());
        String embedding = generateEmbedding(text);
        if (embedding != null) {
            row.setEmbedding(embedding);
        }
    }

    private void applyResourceEmbedding(McpResourceCatalog row) {
        if (row == null) {
            return;
        }
        String text = joinText(row.getResourceUri(), row.getName(), row.getDescription());
        String embedding = generateEmbedding(text);
        if (embedding != null) {
            row.setEmbedding(embedding);
        }
    }

    private String generateEmbedding(String text) {
        try {
            if (text == null || text.isBlank()) {
                return null;
            }
            String vector = llmClientUtil.getEmbedding(text.trim());
            if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) {
                return null;
            }
            return vector;
        } catch (Exception e) {
            log.debug("embedding rebuild failed in catalog sync: {}", e.getMessage());
            return null;
        }
    }

    private String joinText(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(value.trim());
        }
        return sb.toString();
    }
}
