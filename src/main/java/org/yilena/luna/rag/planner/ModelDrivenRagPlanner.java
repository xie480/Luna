package org.yilena.luna.rag.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelDrivenRagPlanner {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;
    private final PromptRegistryService promptRegistryService;

    public QueryPlanDecision planQuery(String original, String normalized, RetrievalRequest request) {
        List<RetrievalRoute> allowedRoutes = request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty()
                ? RetrievalRoute.all()
                : request.getAllowedRoutes();
        List<RetrievalSource> sourceScope = request.getSourceScope() == null || request.getSourceScope().isEmpty()
                ? RetrievalSource.all()
                : request.getSourceScope();

        String prompt = resolvePrompt("rag.planner.query_v1", """
                You are a RAG query planner. Return strict one-line JSON only.
                Required schema:
                {
                  "query_type": "precise_lookup|analysis_reasoning|multi_source_reasoning|general_retrieval",
                  "rewritten_query": "string",
                  "route_hint": "search|native|modular|agentic|none",
                  "complexity": "simple|medium|complex"
                }

                Inputs:
                original_query=%s
                normalized_query=%s
                allowed_routes=%s
                source_scope=%s
                """.formatted(
                safe(original),
                safe(normalized),
                allowedRoutes.stream().map(RetrievalRoute::value).collect(Collectors.joining(",")),
                sourceScope.stream().map(RetrievalSource::value).collect(Collectors.joining(","))
        ));

        JsonNode node = callJson(prompt, selectSmallModel());
        if (node == null || !node.isObject()) {
            return QueryPlanDecision.builder().rewrittenQuery(normalized).complexity("simple").build();
        }

        String queryType = node.path("query_type").asText("");
        String rewrittenQuery = node.path("rewritten_query").asText(normalized);
        String routeHintRaw = node.path("route_hint").asText("none");
        String complexity = node.path("complexity").asText("simple");

        RetrievalRoute routeHint = RetrievalRoute.fromValue(routeHintRaw)
                .filter(allowedRoutes::contains)
                .orElse(null);

        return QueryPlanDecision.builder()
                .queryType(blankToNull(queryType))
                .rewrittenQuery(blankToDefault(rewrittenQuery, normalized))
                .routeHint(routeHint)
                .complexity(blankToDefault(complexity, "simple"))
                .build();
    }

    public SourceProcessPlan planSourceProcessing(
            String query,
            RetrievalSource source,
            int candidateCount,
            int defaultTopK,
            boolean allowRerank,
            boolean allowCompress
    ) {
        if (candidateCount <= 1) {
            return SourceProcessPlan.builder()
                    .deduplicate(true)
                    .rerank(false)
                    .compress(false)
                    .topK(Math.max(1, defaultTopK))
                    .compressionChars(500)
                    .build();
        }

        String prompt = resolvePrompt("rag.planner.source_process_v1", """
                You are a retrieval post-processing policy planner.
                Return strict one-line JSON only.
                Required schema:
                {
                  "deduplicate": true|false,
                  "rerank": true|false,
                  "compress": true|false,
                  "top_k": integer,
                  "compression_chars": integer,
                  "model_tier": "small|mid"
                }

                Inputs:
                query=%s
                source=%s
                candidate_count=%d
                default_top_k=%d
                allow_rerank=%s
                allow_compress=%s
                """.formatted(
                safe(query),
                source.value(),
                candidateCount,
                defaultTopK,
                allowRerank,
                allowCompress
        ));

        JsonNode node = callJson(prompt, selectSmallModel());
        if (node == null || !node.isObject()) {
            return fallbackSourcePlan(defaultTopK, allowRerank, allowCompress);
        }

        boolean dedup = node.path("deduplicate").asBoolean(true);
        boolean rerank = allowRerank && node.path("rerank").asBoolean(allowRerank);
        boolean compress = allowCompress && node.path("compress").asBoolean(allowCompress);
        int topK = clamp(node.path("top_k").asInt(defaultTopK), 1, Math.max(1, defaultTopK));
        int compressionChars = clamp(node.path("compression_chars").asInt(500), 120, 3000);

        return SourceProcessPlan.builder()
                .deduplicate(dedup)
                .rerank(rerank)
                .compress(compress)
                .topK(topK)
                .compressionChars(compressionChars)
                .build();
    }

    public List<AgentStage> planAgentStages(String query, List<RetrievalSource> sourceScope, int maxStages) {
        List<RetrievalSource> safeScope = sourceScope == null || sourceScope.isEmpty() ? RetrievalSource.all() : sourceScope;
        int boundedMaxStages = clamp(maxStages, 1, 5);

        String prompt = resolvePrompt("rag.planner.agent_stage_v1", """
                You are an agentic retrieval planner for multi-stage decomposition.
                Return strict one-line JSON only.
                Required schema:
                {
                  "stages": [
                    {
                      "objective": "string",
                      "rewritten_query": "string",
                      "sources": ["knowledge|memory|preference"]
                    }
                  ]
                }

                Constraints:
                - Keep 1 to %d stages.
                - Prefer minimal stages that cover the task.
                - Sources must be a subset of: %s

                User query: %s
                """.formatted(
                boundedMaxStages,
                safeScope.stream().map(RetrievalSource::value).collect(Collectors.joining(",")),
                safe(query)
        ));

        JsonNode node = callJson(prompt, selectMidModel());
        if (node == null || !node.isObject() || !node.path("stages").isArray()) {
            return List.of(defaultStage(query, safeScope));
        }

        List<AgentStage> stages = new ArrayList<>();
        for (JsonNode stageNode : node.path("stages")) {
            String objective = blankToDefault(stageNode.path("objective").asText(""), "retrieve supporting evidence");
            String rewrittenQuery = blankToDefault(stageNode.path("rewritten_query").asText(""), query);
            List<RetrievalSource> sources = parseSources(stageNode.path("sources"), safeScope);
            stages.add(AgentStage.builder()
                    .objective(objective)
                    .rewrittenQuery(rewrittenQuery)
                    .sources(sources)
                    .build());
        }

        if (stages.isEmpty()) {
            return List.of(defaultStage(query, safeScope));
        }
        return stages.stream().limit(boundedMaxStages).toList();
    }

    public List<Evidence> rerankGlobally(String query, List<Evidence> evidences, int limit, boolean preferMidModel) {
        if (evidences == null || evidences.isEmpty()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, limit);
        if (evidences.size() == 1) {
            return evidences.stream().limit(safeLimit).toList();
        }

        List<Evidence> candidates = evidences.stream().limit(60).toList();
        String docs = candidates.stream()
                .map(it -> "id=" + it.getId() + " | source=" + it.getSource().value() + " | content=" + trimForPrompt(it.getContent(), 200))
                .collect(Collectors.joining("\n"));

        String prompt = resolvePrompt("rag.planner.global_rerank_v1", """
                You are a cross-source reranker.
                Return strict one-line JSON only.
                Required schema: {"ordered_ids": ["evidence_id_1", "evidence_id_2", ...]}

                Query: %s
                Top limit: %d
                Candidate evidences:
                %s
                """.formatted(safe(query), safeLimit, docs));

        JsonNode node = callJson(prompt, preferMidModel ? selectMidModel() : selectSmallModel());
        if (node == null || !node.path("ordered_ids").isArray()) {
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(Evidence::getScore).reversed())
                    .limit(safeLimit)
                    .toList();
        }

        Map<String, Evidence> byId = candidates.stream().collect(Collectors.toMap(Evidence::getId, it -> it, (a, b) -> a, LinkedHashMap::new));
        List<Evidence> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode idNode : node.path("ordered_ids")) {
            String id = idNode.asText("");
            if (id.isBlank() || !seen.add(id)) {
                continue;
            }
            Evidence evidence = byId.get(id);
            if (evidence != null) {
                ordered.add(evidence);
            }
            if (ordered.size() >= safeLimit) {
                break;
            }
        }

        if (ordered.size() < safeLimit) {
            for (Evidence candidate : candidates) {
                if (seen.add(candidate.getId())) {
                    ordered.add(candidate);
                }
                if (ordered.size() >= safeLimit) {
                    break;
                }
            }
        }

        return ordered;
    }

    private SourceProcessPlan fallbackSourcePlan(int defaultTopK, boolean allowRerank, boolean allowCompress) {
        return SourceProcessPlan.builder()
                .deduplicate(true)
                .rerank(allowRerank)
                .compress(allowCompress)
                .topK(Math.max(1, defaultTopK))
                .compressionChars(500)
                .build();
    }

    private List<RetrievalSource> parseSources(JsonNode node, List<RetrievalSource> fallback) {
        if (node == null || !node.isArray()) {
            return fallback;
        }
        List<RetrievalSource> parsed = new ArrayList<>();
        for (JsonNode item : node) {
            Optional<RetrievalSource> source = RetrievalSource.fromValue(item.asText(""));
            source.ifPresent(parsed::add);
        }
        if (parsed.isEmpty()) {
            return fallback;
        }
        return parsed.stream().distinct().filter(fallback::contains).toList();
    }

    private AgentStage defaultStage(String query, List<RetrievalSource> scope) {
        return AgentStage.builder()
                .objective("direct evidence retrieval")
                .rewrittenQuery(query)
                .sources(scope)
                .build();
    }

    private JsonNode callJson(String prompt, String modelName) {
        try {
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(modelName)
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.0)
                    .enablePromptInjectionCheck(false)
                    .build();
            LlmResponse response = llmClientUtil.generate(request);
            if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                return null;
            }
            String cleaned = stripCodeFence(response.getContent());
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("model planner parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String selectSmallModel() {
        if (geminiProperty.getSmall() != null && geminiProperty.getSmall().getModelName() != null) {
            return geminiProperty.getSmall().getModelName();
        }
        if (geminiProperty.getMid() != null && geminiProperty.getMid().getModelName() != null) {
            return geminiProperty.getMid().getModelName();
        }
        return geminiProperty.getBig() != null ? geminiProperty.getBig().getModelName() : null;
    }

    private String selectMidModel() {
        if (geminiProperty.getMid() != null && geminiProperty.getMid().getModelName() != null) {
            return geminiProperty.getMid().getModelName();
        }
        return selectSmallModel();
    }

    private String stripCodeFence(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .trim();
        }
        return cleaned;
    }

    private String trimForPrompt(String input, int maxChars) {
        if (input == null) {
            return "";
        }
        String normalized = input.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\n", " ").replace("\r", " ").trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private String resolvePrompt(String key, String fallback) {
        return promptRegistryService.resolvePromptValue(key, fallback);
    }

    @Value
    @Builder
    public static class QueryPlanDecision {
        String queryType;
        String rewrittenQuery;
        RetrievalRoute routeHint;
        String complexity;
    }

    @Value
    @Builder
    public static class SourceProcessPlan {
        boolean deduplicate;
        boolean rerank;
        boolean compress;
        int topK;
        int compressionChars;
    }

    @Value
    @Builder
    public static class AgentStage {
        String objective;
        String rewrittenQuery;
        List<RetrievalSource> sources;
    }
}
