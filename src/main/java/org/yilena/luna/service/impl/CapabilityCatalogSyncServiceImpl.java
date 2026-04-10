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
/**
 * 能力目录同步服务实现，负责从各个 MCP 服务拉取工具、Prompt 和资源定义，并同步到本地能力目录。
 */
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
        /**
         * 先通过最小同步间隔做节流，避免高频请求反复刷新目录导致数据库和远端服务压力过大。
         */
        long now = System.currentTimeMillis();
        if (now - lastSyncEpochMs < SYNC_MIN_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            /**
             * 在同步锁内再次检查时间窗口，确保并发场景下只有一个线程真正执行同步。
             */
            long guardNow = System.currentTimeMillis();
            if (guardNow - lastSyncEpochMs < SYNC_MIN_INTERVAL_MS) {
                return;
            }

            /**
             * 先加载所有启用中的 MCP 服务；如果外部服务表为空，则退回本地默认服务做能力基线同步。
             */
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

            /**
             * 服务目录同步完成后统一刷新能力注册表，确保工具、Prompt、资源和工作流都能被路由发现。
             */
            refreshCapabilityRegistry();
            lastSyncEpochMs = System.currentTimeMillis();
        }
    }

    private void syncOneServer(String serverCode, McpServerRegistry registry) {
        String target = normalizeServerCode(serverCode);
        try {
            /**
             * 按工具、Prompt、资源三个维度分别拉取并落库，保证目录同步后可按能力类型独立检索。
             */
            List<McpToolDescriptor> tools = mcpClientAdapter.listTools(target);
            upsertToolCatalog(target, tools);
            List<McpPromptDescriptor> prompts = mcpClientAdapter.listPrompts(target);
            upsertPromptCatalog(target, prompts);
            List<McpResourceDescriptor> resources = mcpClientAdapter.listResources(target);
            upsertResourceCatalog(target, resources);

            /**
             * 同步成功后刷新服务健康状态和最后同步时间，便于运维侧判断服务可用性。
             */
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

            /**
             * 先按服务编码和工具名查找现有目录记录，确保同步走更新而不是重复插入。
             */
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

            /**
             * 将远端元数据标准化后写入目录，同时补充向量信息，提升后续语义检索和路由效果。
             */
            row.setTitle(normalizeTitle(descriptor.getTitle(), toolName));
            row.setDescription(descriptor.getDescription());
            row.setInputSchema(descriptor.getInputSchema());
            row.setOutputSchema(descriptor.getOutputSchema());
            row.setExecutionMode("MCP");
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

            /**
             * 同步 Prompt 的标题、说明、参数结构和版本号，保持本地目录与远端注册信息一致。
             */
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

            /**
             * 资源目录除基础元数据外还会重建 embedding，便于后续基于描述和 URI 做资源召回。
             */
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
            /**
             * 优先复用当前同步上下文里的服务记录，拿不到时再补查数据库，减少一次额外查询。
             */
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

            /**
             * 只要本轮目录同步成功，就把服务状态标记为 UP，并更新时间戳。
             */
            row.setLastSyncAt(LocalDateTime.now());
            row.setHealthStatus("UP");
            serverRegistryMapper.updateById(row);
        } catch (Exception e) {
            log.debug("update server sync time failed, serverCode={}, err={}", serverCode, e.getMessage());
        }
    }

    private void refreshCapabilityRegistry() {
        /**
         * 将各类目录统一投影到能力注册表，保证路由层可以用统一能力视图做决策。
         */
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
        /**
         * 组合工具名、标题和说明生成语义向量，便于基于自然语言搜索工具能力。
         */
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
        /**
         * Prompt embedding 用于后续按语义召回 Prompt 或参与能力选择。
         */
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
        /**
         * 资源 embedding 覆盖 URI、名称和说明，降低只靠 URI 匹配带来的召回损失。
         */
        String text = joinText(row.getResourceUri(), row.getName(), row.getDescription());
        String embedding = generateEmbedding(text);
        if (embedding != null) {
            row.setEmbedding(embedding);
        }
    }

    private String generateEmbedding(String text) {
        try {
            /**
             * 只在文本有效时才请求向量，避免无意义调用并过滤掉空向量结果。
             */
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
        /**
         * 统一拼接多个文本字段，为 embedding 生成提供稳定的输入语料。
         */
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
