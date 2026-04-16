package org.yilena.luna.router;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.CapabilityMapper;
import org.yilena.luna.service.CapabilityCatalogSyncService;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
/**
 * 该服务实现负责从能力目录中加载候选，并结合任务阶段、关系状态、权限和风险策略完成能力路由。
 */
public class DefaultCapabilityPolicyRouterService implements CapabilityPolicyRouterService {

    /**
     * 能力目录查询 Mapper。
     */
    private final CapabilityMapper capabilityMapper;
    /**
     * 能力目录同步服务，确保查询前目录数据已更新。
     */
    private final CapabilityCatalogSyncService capabilityCatalogSyncService;
    /**
     * LLM 工具类，用于生成语义向量补充能力召回。
     */
    private final LlmClientUtil llmClientUtil;
    /**
     * 用于解析角色映射和能力元数据。
     */
    private final ObjectMapper objectMapper;

    /**
     * 主体与角色映射配置，用于把当前身份扩展为可参与权限判断的角色集合。
     */
    @Value("${luna.capability.policy.role-mapping-json:{}}")
    private String principalRoleMappingJson;

    @Override
    /**
     * 为上下文构建阶段筛选能力候选，优先保留适合理解、规划和检索的能力。
     */
    public List<Map<String, Object>> routeForContext(String sessionId,
                                                     String query,
                                                     TaskRuntimeState taskState,
                                                     RelationalRuntimeState relationalState,
                                                     int limit) {
        /**
         * 先加载目录候选，再按鉴权规则过滤，最后结合上下文阶段做策略排序。
         */
        List<Map<String, Object>> rows = loadBaseCandidates(query, limit);
        rows = filterByAuthorization(rows);
        return rankByPolicy(rows, query, taskState, relationalState, false, limit);
    }

    // ... existing code ...

    @Override
    /**
     * 为执行阶段筛选能力候选，优先保留可直接调用且风险可控的能力。
     */
    public List<Map<String, Object>> routeForExecution(String sessionId,
                                                       String query,
                                                       TaskRuntimeState taskState,
                                                       RelationalRuntimeState relationalState,
                                                       int limit) {
        /**
         * 执行阶段与上下文阶段共用候选加载和鉴权逻辑，但排序时会启用执行态约束。
         */

        /**
         * 第一步：从能力目录中加载基础候选列表。
         * 该方法会先同步最新的能力目录数据，然后结合词法召回（基于关键词匹配）
         * 和语义召回（基于向量相似度）两种方式获取候选能力，并去重合并结果。
         * 如果查询为空，则返回热门能力列表作为默认候选。
         */
        List<Map<String, Object>> rows = loadBaseCandidates(query, limit);

        /**
         * 第二步：根据当前用户的身份和角色进行权限过滤。
         * 解析用户主体（principal）及其关联的角色集合，检查每个能力的元数据中的
         * 访问控制策略（allowedPrincipals、deniedPrincipals、requiredRoles），
         * 移除用户无权访问的能力，确保后续流程只处理有权限调用的能力。
         */
        rows = filterByAuthorization(rows);

        /**
         * 第三步：根据执行阶段的策略对候选能力进行排序和筛选。
         * 传入 executionOnly=true 表示这是执行阶段的路由，会应用以下额外约束：
         * 1. 排除 STRATEGY 类型的能力（策略类能力仅用于规划阶段，不适合直接执行）
         * 2. 过滤掉高风险或需要审批的能力（通过 isRiskAllowedForExecution 检查）
         * 3. 按类型偏好、风险等级、关键词匹配度等多维度排序
         * 4. 去除重复的能力名称，返回前 limit 个高优先级能力
         *
         * 执行阶段的能力偏好顺序为：TOOL > WORKFLOW > RESOURCE > PROMPT > STRATEGY
         * 这确保了优先选择可直接调用的工具和工作流，而非抽象的策略或提示词。
         */
        return rankByPolicy(rows, query, taskState, relationalState, true, limit);
    }

    // ... existing code ...


    // ... existing code ...

    /**
     * 根据关键词和任务阶段判断是否应进入计划编排流程，避免普通对话误触发规划链路。
     * <p>
     * 该方法的主要逻辑包括：
     * 1. 检查任务运行状态，仅允许PLANNING（规划中）、REPLANNING（重新规划）、CONTEXT_BUILDING（上下文构建）三种状态触发
     * 2. 对查询文本进行标准化处理（转小写、去除特殊字符等）
     * 3. 检测是否包含计划编排相关的关键词（中英文混合支持）
     * <p>
     * 通过状态过滤和关键词匹配的双重校验，确保只有明确的规划意图才会升级到计划编排链路。
     * 支持的关键词包括：计划、规划、方案、roadmap、plan、milestone、拆解、分阶段、replan等。
     *
     * @param query     用户查询文本，用于检测是否包含计划编排相关的关键词
     * @param taskState 任务运行状态，标识当前任务的执行阶段，非规划相关状态直接返回false
     * @return boolean 是否应该触发计划编排流程，true表示需要升级，false表示继续普通工具调用
     */
    @Override
    public boolean shouldTriggerPlanOrchestration(String query, TaskRuntimeState taskState) {
        if (taskState != TaskRuntimeState.PLANNING
                && taskState != TaskRuntimeState.REPLANNING
                && taskState != TaskRuntimeState.CONTEXT_BUILDING) {
            return false;
        }
        String text = normalize(query);
        return containsAny(text, "计划", "规划", "方案", "roadmap", "plan", "milestone", "拆解", "分阶段", "replan");
    }

    // ... existing code ...


    /**
     * 同时融合词法召回和语义召回结果，形成能力路由的基础候选池。
     */
    private List<Map<String, Object>> loadBaseCandidates(String query, int limit) {
        try {
            /**
             * 先同步能力目录，避免基于过期目录做路由决策。
             */
            syncAllCapabilities();
            int safeLimit = Math.max(8, Math.min(limit <= 0 ? 24 : limit, 80));
            String text = query == null ? "" : query.trim();
            if (text.isBlank()) {
                return capabilityMapper.selectTopCapabilities();
            }
            /**
             * 将语义召回与词法召回结果去重合并，尽量兼顾精确匹配和语义相近能力。
             */
            int fetchLimit = Math.max(24, safeLimit * 3);
            List<Map<String, Object>> lexical = capabilityMapper.searchCapabilityCandidates(text, fetchLimit);
            List<Map<String, Object>> semantic = semanticCandidates(text, fetchLimit);
            if (semantic.isEmpty()) {
                return lexical;
            }
            Map<String, Map<String, Object>> merged = new HashMap<>();
            for (Map<String, Object> row : semantic) {
                String key = String.valueOf(row.getOrDefault("capability_name", ""));
                if (!key.isBlank()) {
                    merged.putIfAbsent(key, row);
                }
            }
            for (Map<String, Object> row : lexical) {
                String key = String.valueOf(row.getOrDefault("capability_name", ""));
                if (!key.isBlank()) {
                    merged.putIfAbsent(key, row);
                }
            }
            return new ArrayList<>(merged.values());
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> semanticCandidates(String text, int limit) {
        try {
            String vector = llmClientUtil.getEmbedding(text);
            if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) {
                return Collections.emptyList();
            }
            return capabilityMapper.searchCapabilityCandidatesByVector(vector, limit);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> filterByAuthorization(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        String principal = normalize(AuthContextHolder.getPrincipalKey());
        Set<String> roles = resolvePrincipalRoles(principal);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (isAllowed(row, principal, roles)) {
                out.add(row);
            }
        }
        return out;
    }

    private boolean isAllowed(Map<String, Object> row, String principal, Set<String> roles) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        Map<String, Object> metadata = parseMetadata(row.get("metadata_json"));
        if (metadata.isEmpty()) {
            return true;
        }

        Set<String> deniedPrincipals = extractStringSet(metadata.get("deniedPrincipals"));
        if (matchPrincipal(principal, deniedPrincipals)) {
            return false;
        }

        Set<String> allowedPrincipals = extractStringSet(metadata.get("allowedPrincipals"));
        if (!allowedPrincipals.isEmpty() && !matchPrincipal(principal, allowedPrincipals)) {
            return false;
        }

        Set<String> requiredRoles = extractStringSet(metadata.get("requiredRoles"));
        if (requiredRoles.isEmpty()) {
            return true;
        }
        for (String role : requiredRoles) {
            if ("*".equals(role) || roles.contains(role.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchPrincipal(String principal, Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        if (candidates.contains("*")) {
            return true;
        }
        if (principal == null || principal.isBlank()) {
            return false;
        }
        if (candidates.contains(principal)) {
            return true;
        }
        int idx = principal.indexOf(':');
        if (idx > 0 && idx + 1 < principal.length()) {
            return candidates.contains(principal.substring(idx + 1));
        }
        return false;
    }

    private Set<String> resolvePrincipalRoles(String principal) {
        Set<String> out = new HashSet<>();
        if (principal != null && !principal.isBlank()) {
            out.add(principal);
        }
        Map<String, List<String>> mapping = parseRoleMapping();
        List<String> direct = mapping.getOrDefault(principal, List.of());
        for (String role : direct) {
            if (role != null && !role.isBlank()) {
                out.add(role.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private Map<String, List<String>> parseRoleMapping() {
        if (principalRoleMappingJson == null || principalRoleMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(principalRoleMappingJson, new TypeReference<>() {
            });
            Map<String, List<String>> out = new HashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                Set<String> roles = extractStringSet(entry.getValue());
                out.put(normalize(entry.getKey()), new ArrayList<>(roles));
            }
            return out;
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private Map<String, Object> parseMetadata(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new HashMap<>();
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

    private Set<String> extractStringSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String text = normalize(item == null ? "" : String.valueOf(item));
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<Object> parsed = objectMapper.readValue(text, new TypeReference<>() {
                });
                for (Object item : parsed) {
                    String one = normalize(item == null ? "" : String.valueOf(item));
                    if (!one.isBlank()) {
                        out.add(one);
                    }
                }
                return out;
            } catch (Exception ignore) {
                return Set.of();
            }
        }
        Arrays.stream(text.split(","))
                .map(this::normalize)
                .filter(s -> !s.isBlank())
                .forEach(out::add);
        return out;
    }

    private void syncAllCapabilities() {
        capabilityCatalogSyncService.syncFromServers();
    }

    /**
     * 结合阶段偏好、风险等级和关键词命中情况对候选能力做策略排序。
     */
    private List<Map<String, Object>> rankByPolicy(List<Map<String, Object>> rows,
                                                   String query,
                                                   TaskRuntimeState taskState,
                                                   RelationalRuntimeState relationalState,
                                                   boolean executionOnly,
                                                   int limit) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        /**
         * 先按阶段推断能力类型偏好，再用风险和文本命中情况细化排序。
         */
        List<String> preferredTypes = preferredTypes(taskState, relationalState);
        String q = normalize(query);

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt((Map<String, Object> row) -> typePenalty(typeOf(row), preferredTypes))
                .thenComparingInt(this::riskPenalty)
                .thenComparingInt(row -> keywordPenalty(q, row))
                .thenComparing(row -> String.valueOf(row.getOrDefault("capability_name", ""))));

        /**
         * 最后按执行场景限制与去重规则截取结果，确保后续流程只看到可用且高优先级的能力。
         */
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        int safeLimit = Math.max(1, limit <= 0 ? 24 : limit);
        for (Map<String, Object> row : sorted) {
            String type = typeOf(row);
            if (executionOnly && "STRATEGY".equals(type)) {
                continue;
            }
            if (executionOnly && !isRiskAllowedForExecution(row)) {
                continue;
            }
            String capabilityName = String.valueOf(row.getOrDefault("capability_name", ""));
            if (capabilityName.isBlank() || !seen.add(capabilityName)) {
                continue;
            }
            out.add(row);
            if (out.size() >= safeLimit) {
                break;
            }
        }
        return out;
    }

    private List<String> preferredTypes(TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        if (relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == RelationalRuntimeState.REPAIRING) {
            return List.of("STRATEGY", "PROMPT", "RESOURCE", "WORKFLOW", "TOOL");
        }
        if (taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING) {
            return List.of("STRATEGY", "WORKFLOW", "TOOL", "PROMPT", "RESOURCE");
        }
        if (taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL) {
            return List.of("TOOL", "WORKFLOW", "RESOURCE", "PROMPT", "STRATEGY");
        }
        return List.of("TOOL", "WORKFLOW", "PROMPT", "RESOURCE", "STRATEGY");
    }

    private int typePenalty(String type, List<String> preferredTypes) {
        int idx = preferredTypes.indexOf(type);
        return idx < 0 ? preferredTypes.size() : idx;
    }

    private int keywordPenalty(String query, Map<String, Object> row) {
        if (query.isBlank()) {
            return 0;
        }
        String name = normalize(String.valueOf(row.getOrDefault("capability_name", "")));
        String title = normalize(String.valueOf(row.getOrDefault("title", "")));
        String desc = normalize(String.valueOf(row.getOrDefault("description", "")));
        if (name.contains(query)) {
            return 0;
        }
        if (title.contains(query)) {
            return 1;
        }
        if (desc.contains(query)) {
            return 2;
        }
        return 3;
    }

    private String typeOf(Map<String, Object> row) {
        return String.valueOf(row.getOrDefault("capability_type", "")).toUpperCase(Locale.ROOT);
    }

    private boolean isRiskAllowedForExecution(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        return true;
    }

    private int riskPenalty(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return 10;
        }
        boolean requiresApproval = boolVal(row.get("requires_approval"));
        String sensitivity = normalize(String.valueOf(row.getOrDefault("sensitivity", "LOW"))).toUpperCase(Locale.ROOT);
        int penalty = switch (sensitivity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 0;
        };
        if (requiresApproval) {
            penalty += 2;
        }
        return penalty;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean boolVal(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }
}
