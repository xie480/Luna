package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.entity.McpPromptCatalog;
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpToolCatalog;
import org.yilena.luna.entity.McpToolImplMapping;
import org.yilena.luna.entity.WorkflowTemplate;
import org.yilena.luna.service.CapabilityCatalogSyncService;
import org.yilena.luna.service.McpService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpCatalogMigrationJob {

    private static final Set<String> CORE_LOCAL_HANDLER_TOOLS = Set.of(
            "manage_memory",
            "manage_schedule_task",
            "manage_knowledge_base",
            "manage_log",
            "web_search",
            "image_search",
            "news_search",
            "lens_search",
            "web_scrape"
    );

    private final JdbcTemplate jdbcTemplate;
    private final McpService mcpService;
    private final CapabilityCatalogSyncService capabilityCatalogSyncService;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${luna.mcp.migration.auto-enabled:false}")
    private boolean autoEnabled;

    @Value("${luna.mcp.migration.validation-enabled:true}")
    private boolean validationEnabled;

    @Value("${luna.mcp.migration.read-legacy-enabled:false}")
    private boolean readLegacyEnabled;

    @Value("${luna.mcp.migration.fail-on-legacy-pending:false}")
    private boolean failOnLegacyPending;

    @PostConstruct
    public void migrateOnStartup() {
        if (!autoEnabled) {
            return;
        }
        runMigration("startup");
    }

    @Scheduled(cron = "${luna.mcp.migration.validation.cron:0 */30 * * * *}")
    public void validateAndMigrate() {
        if (!validationEnabled) {
            return;
        }
        validateSnapshot("scheduled", 0, 0, 0, 0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void runMigration(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!readLegacyEnabled) {
                reportLegacyPendingRetirement(trigger);
                ensureCoreLocalHandlerMappings();
                validateSnapshot(trigger, 0, 0, 0, 0);
                return;
            }
            if (!tableExists("mcp_tools") && !tableExists("mcp_skills")) {
                ensureCoreLocalHandlerMappings();
                validateSnapshot(trigger, 0, 0, 0, 0);
                return;
            }

            ensureLocalServerRegistry();

            int migratedTools = migrateLegacyTools();
            int migratedSkillsAsPrompt = migrateLegacySkillsAsPrompt();
            int migratedSkillsAsCompositeTool = migrateLegacySkillsAsCompositeTool();
            int migratedSkillsAsWorkflow = migrateLegacySkillsAsWorkflow();
            ensureCoreLocalHandlerMappings();

            capabilityCatalogSyncService.syncFromServers();
            validateSnapshot(trigger, migratedTools, migratedSkillsAsPrompt, migratedSkillsAsWorkflow, migratedSkillsAsCompositeTool);
        } catch (Exception e) {
            log.warn("mcp auto migration failed, trigger={}, err={}", trigger, e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    private void ensureLocalServerRegistry() {
        McpServerRegistry registry = McpServerRegistry.builder()
                .serverCode(McpConstant.LOCAL_SERVER_CODE)
                .serverName("Local Agent MCP Server")
                .description("Auto-maintained local MCP server registry")
                .transportType("LOCAL")
                .enabled(true)
                .healthStatus("UP")
                .lastSyncAt(LocalDateTime.now())
                .build();
        mcpService.upsertServerRegistry(registry);
    }

    private int migrateLegacyTools() {
        if (!tableExists("mcp_tools")) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, description, version, owner, bean_name, method_name,
                       input_schema, output_schema, embedding, requires_approval, sensitivity
                from mcp_tools
                """);
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String toolName = text(row.get("name"));
            if (toolName.isBlank()) {
                continue;
            }
            McpToolCatalog catalog = McpToolCatalog.builder()
                    .id(longValue(row.get("id")))
                    .serverCode(McpConstant.LOCAL_SERVER_CODE)
                    .toolName(toolName)
                    .title(toolName)
                    .description(text(row.get("description")))
                    .inputSchema(parseJsonMap(row.get("input_schema")))
                    .outputSchema(parseJsonMap(row.get("output_schema")))
                    .enabled(true)
                    .version(defaultValue(text(row.get("version")), "1.0.0"))
                    .executionMode(isCoreLocalHandlerTool(toolName) ? "MCP" : "LEGACY")
                    .requiresApproval(boolValue(row.get("requires_approval")))
                    .sensitivity(defaultValue(text(row.get("sensitivity")).toUpperCase(Locale.ROOT), "LOW"))
                    .rawPayload(legacyToolRawPayload(row))
                    .embedding(text(row.get("embedding")))
                    .syncedAt(LocalDateTime.now())
                    .build();
            mcpService.upsertToolCatalog(catalog);

            McpToolImplMapping mapping = McpToolImplMapping.builder()
                    .serverCode(McpConstant.LOCAL_SERVER_CODE)
                    .toolName(toolName)
                    .implType(resolveToolImplType(toolName))
                    .beanName(text(row.get("bean_name")))
                    .methodName(text(row.get("method_name")))
                    .timeoutMs(10000)
                    .enabled(isCoreLocalHandlerTool(toolName))
                    .executionMode(isCoreLocalHandlerTool(toolName) ? "MCP" : "LEGACY")
                    .build();
            mcpService.upsertToolImplMapping(mapping);
            migrated++;
        }
        return migrated;
    }

    private int migrateLegacySkillsAsPrompt() {
        if (!tableExists("mcp_skills")) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, description, version, owner, bean_name, method_name,
                       input_schema, output_schema, run_mode,
                       required_capabilities, tool_slots, thought_chain, embedding
                from mcp_skills
                """);
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            if (!isPromptLike(row)) {
                continue;
            }
            String name = text(row.get("name"));
            if (name.isBlank()) {
                continue;
            }
            McpPromptCatalog prompt = McpPromptCatalog.builder()
                    .id(longValue(row.get("id")))
                    .serverCode(McpConstant.LOCAL_SERVER_CODE)
                    .promptName(name)
                    .title(name)
                    .description(text(row.get("description")))
                    .argumentsSchema(parseJsonMap(row.get("input_schema")))
                    .rawPayload(legacySkillRawPayload(row))
                    .enabled(true)
                    .version(defaultValue(text(row.get("version")), "1.0.0"))
                    .embedding(text(row.get("embedding")))
                    .syncedAt(LocalDateTime.now())
                    .build();
            mcpService.upsertPromptCatalog(prompt);
            migrated++;
        }
        return migrated;
    }

    private int migrateLegacySkillsAsCompositeTool() {
        if (!tableExists("mcp_skills")) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, description, version, owner, bean_name, method_name,
                       input_schema, output_schema, run_mode,
                       required_capabilities, tool_slots, thought_chain, embedding
                from mcp_skills
                """);
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            if (!isCompositeToolLike(row)) {
                continue;
            }
            String name = text(row.get("name"));
            if (name.isBlank()) {
                continue;
            }
            McpToolCatalog tool = McpToolCatalog.builder()
                    .id(longValue(row.get("id")))
                    .serverCode(McpConstant.LOCAL_SERVER_CODE)
                    .toolName(name)
                    .title(name)
                    .description(text(row.get("description")))
                    .inputSchema(parseJsonMap(row.get("input_schema")))
                    .outputSchema(parseJsonMap(row.get("output_schema")))
                    .enabled(true)
                    .version(defaultValue(text(row.get("version")), "1.0.0"))
                    .requiresApproval(false)
                    .sensitivity("LOW")
                    .executionMode("MCP")
                    .rawPayload(legacyCompositeSkillRawPayload(row))
                    .embedding(text(row.get("embedding")))
                    .syncedAt(LocalDateTime.now())
                    .build();
            mcpService.upsertToolCatalog(tool);

            // Composite tool is executed by CompositeWorkflowLocalMcpToolHandler through workflow_template.
            McpToolImplMapping mapping = McpToolImplMapping.builder()
                    .serverCode(McpConstant.LOCAL_SERVER_CODE)
                    .toolName(name)
                    .implType("LOCAL_HANDLER")
                    .timeoutMs(10000)
                    .enabled(true)
                    .executionMode("MCP")
                    .build();
            mcpService.upsertToolImplMapping(mapping);
            migrated++;
        }
        return migrated;
    }

    private int migrateLegacySkillsAsWorkflow() {
        if (!tableExists("mcp_skills")) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, name, description, version,
                       input_schema, output_schema, run_mode,
                       required_capabilities, tool_slots, thought_chain, embedding
                from mcp_skills
                """);
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            if (!isWorkflowLike(row) || isCompositeToolLike(row)) {
                continue;
            }
            String name = text(row.get("name"));
            if (name.isBlank()) {
                continue;
            }
            WorkflowTemplate workflow = WorkflowTemplate.builder()
                    .id(longValue(row.get("id")))
                    .workflowName(name)
                    .description(text(row.get("description")))
                    .inputSchema(parseJsonMap(row.get("input_schema")))
                    .outputSchema(parseJsonMap(row.get("output_schema")))
                    .requiredCapabilities(parseJsonStringList(row.get("required_capabilities")))
                    .toolSlots(parseJsonMapList(row.get("tool_slots")))
                    .thoughtChain(parseJsonStringList(row.get("thought_chain")))
                    .enabled(true)
                    .version(defaultValue(text(row.get("version")), "1.0.0"))
                    .embedding(text(row.get("embedding")))
                    .build();
            mcpService.upsertWorkflowTemplate(workflow);
            migrated++;
        }
        return migrated;
    }

    private void ensureCoreLocalHandlerMappings() {
        if (!tableExists("mcp_tool_catalog")) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select server_code, tool_name
                from mcp_tool_catalog
                where lower(tool_name) in ('manage_memory','manage_schedule_task','manage_knowledge_base','manage_log',
                                           'web_search','image_search','news_search','lens_search','web_scrape')
                """);
        for (Map<String, Object> row : rows) {
            String serverCode = defaultValue(text(row.get("server_code")), McpConstant.LOCAL_SERVER_CODE);
            String toolName = text(row.get("tool_name"));
            if (toolName.isBlank()) {
                continue;
            }
            McpToolImplMapping mapping = McpToolImplMapping.builder()
                    .serverCode(serverCode)
                    .toolName(toolName)
                    .implType("LOCAL_HANDLER")
                    .enabled(true)
                    .timeoutMs(10000)
                    .executionMode("MCP")
                    .build();
            mcpService.upsertToolImplMapping(mapping);
        }
    }

    private void validateSnapshot(String trigger,
                                  int migratedTools,
                                  int migratedPrompts,
                                  int migratedWorkflows,
                                  int migratedCompositeTools) {
        long legacyTools = readLegacyEnabled ? countIfTableExists("mcp_tools") : 0L;
        long legacySkills = readLegacyEnabled ? countIfTableExists("mcp_skills") : 0L;
        long catalogTools = countIfTableExists("mcp_tool_catalog");
        long implMappings = countIfTableExists("mcp_tool_impl_mapping");
        long catalogPrompts = countIfTableExists("mcp_prompt_catalog");
        long workflows = countIfTableExists("workflow_template");

        if (legacyTools > 0 && (catalogTools < legacyTools || implMappings < legacyTools)) {
            log.warn("mcp migration validation warning: tool coverage incomplete, trigger={}, legacyTools={}, catalogTools={}, implMappings={}",
                    trigger, legacyTools, catalogTools, implMappings);
        }
        if (legacySkills > 0 && (catalogPrompts + workflows + migratedCompositeTools) < legacySkills) {
            log.warn("mcp migration validation warning: skill coverage incomplete, trigger={}, legacySkills={}, prompts={}, workflows={}, compositeTools={}",
                    trigger, legacySkills, catalogPrompts, workflows, migratedCompositeTools);
        }

        log.info("mcp migration snapshot: trigger={}, migratedTools={}, migratedPrompts={}, migratedWorkflows={}, migratedCompositeTools={}, legacyTools={}, legacySkills={}, catalogTools={}, implMappings={}, prompts={}, workflows={}",
                trigger, migratedTools, migratedPrompts, migratedWorkflows, migratedCompositeTools, legacyTools, legacySkills, catalogTools, implMappings, catalogPrompts, workflows);
    }

    private void reportLegacyPendingRetirement(String trigger) {
        long legacyTools = countIfTableExists("mcp_tools");
        long legacySkills = countIfTableExists("mcp_skills");
        if (legacyTools <= 0 && legacySkills <= 0) {
            return;
        }
        String message = "legacy MCP tables still online while read-legacy is disabled, trigger=" + trigger
                + ", mcp_tools=" + legacyTools + ", mcp_skills=" + legacySkills;
        if (failOnLegacyPending) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    private Map<String, Object> legacyToolRawPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", text(row.get("name")));
        payload.put("description", text(row.get("description")));
        payload.put("version", defaultValue(text(row.get("version")), "1.0.0"));
        payload.put("owner", text(row.get("owner")));
        payload.put("beanName", text(row.get("bean_name")));
        payload.put("methodName", text(row.get("method_name")));
        payload.put("inputSchema", parseJsonMap(row.get("input_schema")));
        payload.put("outputSchema", parseJsonMap(row.get("output_schema")));
        return payload;
    }

    private Map<String, Object> legacySkillRawPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skillName", text(row.get("name")));
        payload.put("legacyBeanName", text(row.get("bean_name")));
        payload.put("legacyMethodName", text(row.get("method_name")));
        payload.put("runMode", defaultValue(text(row.get("run_mode")).toUpperCase(Locale.ROOT), "SYNC"));
        return payload;
    }

    private Map<String, Object> legacyCompositeSkillRawPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("migrationType", "COMPOSITE_TOOL");
        payload.put("skillName", text(row.get("name")));
        payload.put("legacyBeanName", text(row.get("bean_name")));
        payload.put("legacyMethodName", text(row.get("method_name")));
        payload.put("runMode", defaultValue(text(row.get("run_mode")).toUpperCase(Locale.ROOT), "SYNC"));
        payload.put("requiredCapabilities", parseJsonStringList(row.get("required_capabilities")));
        payload.put("toolSlots", parseJsonMapList(row.get("tool_slots")));
        payload.put("thoughtChain", parseJsonStringList(row.get("thought_chain")));
        return payload;
    }

    private boolean isWorkflowLike(Map<String, Object> row) {
        String runMode = text(row.get("run_mode")).toUpperCase(Locale.ROOT);
        if ("ASYNC".equals(runMode)) {
            return true;
        }
        if (!parseJsonStringList(row.get("required_capabilities")).isEmpty()) {
            return true;
        }
        if (!parseJsonMapList(row.get("tool_slots")).isEmpty()) {
            return true;
        }
        return !parseJsonStringList(row.get("thought_chain")).isEmpty();
    }

    private boolean isCompositeToolLike(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        String runMode = text(row.get("run_mode")).toUpperCase(Locale.ROOT);
        if ("ASYNC".equals(runMode)) {
            return false;
        }
        String beanName = text(row.get("bean_name"));
        String methodName = text(row.get("method_name"));
        if (beanName.isBlank() || methodName.isBlank()) {
            return false;
        }
        return isWorkflowLike(row);
    }

    private boolean isPromptLike(Map<String, Object> row) {
        return !isWorkflowLike(row);
    }

    private String resolveToolImplType(String toolName) {
        return isCoreLocalHandlerTool(toolName) ? "LOCAL_HANDLER" : "SPRING_BEAN";
    }

    private boolean isCoreLocalHandlerTool(String toolName) {
        return CORE_LOCAL_HANDLER_TOOLS.contains(text(toolName).toLowerCase(Locale.ROOT));
    }

    private boolean tableExists(String tableName) {
        try {
            String table = jdbcTemplate.queryForObject(
                    "select to_regclass(?)",
                    String.class,
                    "public." + tableName
            );
            return table != null && !table.isBlank();
        } catch (Exception ignore) {
            return false;
        }
    }

    private long countIfTableExists(String tableName) {
        if (!tableExists(tableName)) {
            return 0L;
        }
        try {
            return jdbcTemplate.queryForObject("select count(1) from " + tableName, Long.class);
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private List<String> parseJsonStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        try {
            List<Object> list = objectMapper.readValue(text, new TypeReference<>() {
            });
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parseJsonMapList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    map.forEach((k, v) -> one.put(String.valueOf(k), v));
                    out.add(one);
                }
            }
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private Long longValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean boolValue(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "yes".equals(value);
    }

    private String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private String defaultValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
