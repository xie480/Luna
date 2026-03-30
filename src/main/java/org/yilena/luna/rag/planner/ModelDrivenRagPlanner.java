package org.yilena.luna.rag.planner; // define package

import com.fasterxml.jackson.databind.JsonNode; // import dependency
import com.fasterxml.jackson.databind.ObjectMapper; // import dependency
import lombok.Builder; // import dependency
import lombok.RequiredArgsConstructor; // import dependency
import lombok.Value; // import dependency
import lombok.extern.slf4j.Slf4j; // import dependency
import org.springframework.stereotype.Component; // import dependency
import org.yilena.luna.enums.ModelType; // import dependency
import org.yilena.luna.llm.LlmMessage; // import dependency
import org.yilena.luna.llm.LlmRequest; // import dependency
import org.yilena.luna.llm.LlmResponse; // import dependency
import org.yilena.luna.properties.GeminiProperty; // import dependency
import org.yilena.luna.rag.models.Evidence; // import dependency
import org.yilena.luna.rag.models.RetrievalRequest; // import dependency
import org.yilena.luna.rag.models.RetrievalRoute; // import dependency
import org.yilena.luna.rag.models.RetrievalSource; // import dependency
import org.yilena.luna.utils.LlmClientUtil; // import dependency

import java.util.ArrayList; // import dependency
import java.util.Collections; // import dependency
import java.util.Comparator; // import dependency
import java.util.LinkedHashMap; // import dependency
import java.util.LinkedHashSet; // import dependency
import java.util.List; // import dependency
import java.util.Map; // import dependency
import java.util.Optional; // import dependency
import java.util.Set; // import dependency
import java.util.stream.Collectors; // import dependency

@Slf4j // declare annotation
@Component // declare annotation
@RequiredArgsConstructor // declare annotation
public class ModelDrivenRagPlanner { // define class

    private final LlmClientUtil llmClientUtil; // business logic
    private final GeminiProperty geminiProperty; // business logic
    private final ObjectMapper objectMapper; // business logic

    public QueryPlanDecision planQuery(String original, String normalized, RetrievalRequest request) { // method definition
        List<RetrievalRoute> allowedRoutes = request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty() // assignment or init
                ? RetrievalRoute.all() // business logic
                : request.getAllowedRoutes(); // business logic
        List<RetrievalSource> sourceScope = request.getSourceScope() == null || request.getSourceScope().isEmpty() // assignment or init
                ? RetrievalSource.all() // business logic
                : request.getSourceScope(); // business logic

        String prompt = """ // assignment or init
                You are a RAG query planner. Return strict one-line JSON only. // return result
                Required schema: // business logic
                { // block start
                  "query_type": "precise_lookup|analysis_reasoning|multi_source_reasoning|general_retrieval", // business logic
                  "rewritten_query": "string", // business logic
                  "route_hint": "search|native|modular|agentic|none", // business logic
                  "complexity": "simple|medium|complex" // business logic
                } // block end

                Inputs: // business logic
                original_query=%s // assignment or init
                normalized_query=%s // assignment or init
                allowed_routes=%s // assignment or init
                source_scope=%s // assignment or init
                """.formatted( // business logic
                safe(original), // enum or const item
                safe(normalized), // enum or const item
                allowedRoutes.stream().map(RetrievalRoute::value).collect(Collectors.joining(",")), // business logic
                sourceScope.stream().map(RetrievalSource::value).collect(Collectors.joining(",")) // business logic
        ); // business logic

        JsonNode node = callJson(prompt, selectSmallModel()); // assignment or init
        if (node == null || !node.isObject()) { // branch logic
            return QueryPlanDecision.builder().rewrittenQuery(normalized).complexity("simple").build(); // return result
        } // block end

        String queryType = node.path("query_type").asText(""); // assignment or init
        String rewrittenQuery = node.path("rewritten_query").asText(normalized); // assignment or init
        String routeHintRaw = node.path("route_hint").asText("none"); // assignment or init
        String complexity = node.path("complexity").asText("simple"); // assignment or init

        RetrievalRoute routeHint = RetrievalRoute.fromValue(routeHintRaw) // assignment or init
                .filter(allowedRoutes::contains) // business logic
                .orElse(null); // business logic

        return QueryPlanDecision.builder() // return result
                .queryType(blankToNull(queryType)) // business logic
                .rewrittenQuery(blankToDefault(rewrittenQuery, normalized)) // business logic
                .routeHint(routeHint) // business logic
                .complexity(blankToDefault(complexity, "simple")) // business logic
                .build(); // business logic
    } // block end

    public SourceProcessPlan planSourceProcessing( // business logic
            String query, // business logic
            RetrievalSource source, // business logic
            int candidateCount, // business logic
            int defaultTopK, // business logic
            boolean allowRerank, // business logic
            boolean allowCompress // business logic
    ) { // block start
        if (candidateCount <= 1) { // branch logic
            return SourceProcessPlan.builder() // return result
                    .deduplicate(true) // business logic
                    .rerank(false) // business logic
                    .compress(false) // business logic
                    .topK(Math.max(1, defaultTopK)) // business logic
                    .compressionChars(500) // business logic
                    .build(); // business logic
        } // block end

        String prompt = """ // assignment or init
                You are a retrieval post-processing policy planner. // business logic
                Return strict one-line JSON only. // return result
                Required schema: // business logic
                { // block start
                  "deduplicate": true|false, // business logic
                  "rerank": true|false, // business logic
                  "compress": true|false, // business logic
                  "top_k": integer, // business logic
                  "compression_chars": integer, // business logic
                  "model_tier": "small|mid" // business logic
                } // block end

                Inputs: // business logic
                query=%s // assignment or init
                source=%s // assignment or init
                candidate_count=%d // assignment or init
                default_top_k=%d // assignment or init
                allow_rerank=%s // assignment or init
                allow_compress=%s // assignment or init
                """.formatted( // business logic
                safe(query), // enum or const item
                source.value(), // business logic
                candidateCount, // enum or const item
                defaultTopK, // enum or const item
                allowRerank, // enum or const item
                allowCompress // business logic
        ); // business logic

        JsonNode node = callJson(prompt, selectSmallModel()); // assignment or init
        if (node == null || !node.isObject()) { // branch logic
            return fallbackSourcePlan(defaultTopK, allowRerank, allowCompress); // return result
        } // block end

        boolean dedup = node.path("deduplicate").asBoolean(true); // assignment or init
        boolean rerank = allowRerank && node.path("rerank").asBoolean(allowRerank); // assignment or init
        boolean compress = allowCompress && node.path("compress").asBoolean(allowCompress); // assignment or init
        int topK = clamp(node.path("top_k").asInt(defaultTopK), 1, Math.max(1, defaultTopK)); // assignment or init
        int compressionChars = clamp(node.path("compression_chars").asInt(500), 120, 3000); // assignment or init

        return SourceProcessPlan.builder() // return result
                .deduplicate(dedup) // business logic
                .rerank(rerank) // business logic
                .compress(compress) // business logic
                .topK(topK) // business logic
                .compressionChars(compressionChars) // business logic
                .build(); // business logic
    } // block end

    public List<AgentStage> planAgentStages(String query, List<RetrievalSource> sourceScope, int maxStages) { // method definition
        List<RetrievalSource> safeScope = sourceScope == null || sourceScope.isEmpty() ? RetrievalSource.all() : sourceScope; // assignment or init
        int boundedMaxStages = clamp(maxStages, 1, 5); // assignment or init

        String prompt = """ // assignment or init
                You are an agentic retrieval planner for multi-stage decomposition. // loop logic
                Return strict one-line JSON only. // return result
                Required schema: // business logic
                { // block start
                  "stages": [ // business logic
                    { // block start
                      "objective": "string", // business logic
                      "rewritten_query": "string", // business logic
                      "sources": ["knowledge|memory|preference"] // business logic
                    } // block end
                  ] // business logic
                } // block end

                Constraints: // business logic
                - Keep 1 to %d stages. // business logic
                - Prefer minimal stages that cover the task. // business logic
                - Sources must be a subset of: %s // business logic

                User query: %s // business logic
                """.formatted( // business logic
                boundedMaxStages, // enum or const item
                safeScope.stream().map(RetrievalSource::value).collect(Collectors.joining(",")), // business logic
                safe(query) // enum or const item
        ); // business logic

        JsonNode node = callJson(prompt, selectMidModel()); // assignment or init
        if (node == null || !node.isObject() || !node.path("stages").isArray()) { // branch logic
            return List.of(defaultStage(query, safeScope)); // return result
        } // block end

        List<AgentStage> stages = new ArrayList<>(); // assignment or init
        for (JsonNode stageNode : node.path("stages")) { // loop logic
            String objective = blankToDefault(stageNode.path("objective").asText(""), "retrieve supporting evidence"); // assignment or init
            String rewrittenQuery = blankToDefault(stageNode.path("rewritten_query").asText(""), query); // assignment or init
            List<RetrievalSource> sources = parseSources(stageNode.path("sources"), safeScope); // assignment or init
            stages.add(AgentStage.builder() // business logic
                    .objective(objective) // business logic
                    .rewrittenQuery(rewrittenQuery) // business logic
                    .sources(sources) // business logic
                    .build()); // business logic
        } // block end

        if (stages.isEmpty()) { // branch logic
            return List.of(defaultStage(query, safeScope)); // return result
        } // block end
        return stages.stream().limit(boundedMaxStages).toList(); // return result
    } // block end

    public List<Evidence> rerankGlobally(String query, List<Evidence> evidences, int limit, boolean preferMidModel) { // method definition
        if (evidences == null || evidences.isEmpty()) { // branch logic
            return Collections.emptyList(); // return result
        } // block end
        int safeLimit = Math.max(1, limit); // assignment or init
        if (evidences.size() == 1) { // branch logic
            return evidences.stream().limit(safeLimit).toList(); // return result
        } // block end

        List<Evidence> candidates = evidences.stream().limit(60).toList(); // assignment or init
        String docs = candidates.stream() // assignment or init
                .map(it -> "id=" + it.getId() + " | source=" + it.getSource().value() + " | content=" + trimForPrompt(it.getContent(), 200)) // assignment or init
                .collect(Collectors.joining("\n")); // business logic

        String prompt = """ // assignment or init
                You are a cross-source reranker. // business logic
                Return strict one-line JSON only. // return result
                Required schema: {"ordered_ids": ["evidence_id_1", "evidence_id_2", ...]} // business logic

                Query: %s // business logic
                Top limit: %d // business logic
                Candidate evidences: // business logic
                %s // business logic
                """.formatted(safe(query), safeLimit, docs); // business logic

        JsonNode node = callJson(prompt, preferMidModel ? selectMidModel() : selectSmallModel()); // assignment or init
        if (node == null || !node.path("ordered_ids").isArray()) { // branch logic
            return candidates.stream() // return result
                    .sorted(Comparator.comparingDouble(Evidence::getScore).reversed()) // business logic
                    .limit(safeLimit) // business logic
                    .toList(); // business logic
        } // block end

        Map<String, Evidence> byId = candidates.stream().collect(Collectors.toMap(Evidence::getId, it -> it, (a, b) -> a, LinkedHashMap::new)); // assignment or init
        List<Evidence> ordered = new ArrayList<>(); // assignment or init
        Set<String> seen = new LinkedHashSet<>(); // assignment or init
        for (JsonNode idNode : node.path("ordered_ids")) { // loop logic
            String id = idNode.asText(""); // assignment or init
            if (id.isBlank() || !seen.add(id)) { // branch logic
                continue; // enum or const item
            } // block end
            Evidence evidence = byId.get(id); // assignment or init
            if (evidence != null) { // branch logic
                ordered.add(evidence); // business logic
            } // block end
            if (ordered.size() >= safeLimit) { // branch logic
                break; // enum or const item
            } // block end
        } // block end

        if (ordered.size() < safeLimit) { // branch logic
            for (Evidence candidate : candidates) { // loop logic
                if (seen.add(candidate.getId())) { // branch logic
                    ordered.add(candidate); // business logic
                } // block end
                if (ordered.size() >= safeLimit) { // branch logic
                    break; // enum or const item
                } // block end
            } // block end
        } // block end

        return ordered; // return result
    } // block end

    private SourceProcessPlan fallbackSourcePlan(int defaultTopK, boolean allowRerank, boolean allowCompress) { // method definition
        return SourceProcessPlan.builder() // return result
                .deduplicate(true) // business logic
                .rerank(allowRerank) // business logic
                .compress(allowCompress) // business logic
                .topK(Math.max(1, defaultTopK)) // business logic
                .compressionChars(500) // business logic
                .build(); // business logic
    } // block end

    private List<RetrievalSource> parseSources(JsonNode node, List<RetrievalSource> fallback) { // method definition
        if (node == null || !node.isArray()) { // branch logic
            return fallback; // return result
        } // block end
        List<RetrievalSource> parsed = new ArrayList<>(); // assignment or init
        for (JsonNode item : node) { // loop logic
            Optional<RetrievalSource> source = RetrievalSource.fromValue(item.asText("")); // assignment or init
            source.ifPresent(parsed::add); // business logic
        } // block end
        if (parsed.isEmpty()) { // branch logic
            return fallback; // return result
        } // block end
        return parsed.stream().distinct().filter(fallback::contains).toList(); // return result
    } // block end

    private AgentStage defaultStage(String query, List<RetrievalSource> scope) { // method definition
        return AgentStage.builder() // return result
                .objective("direct evidence retrieval") // business logic
                .rewrittenQuery(query) // business logic
                .sources(scope) // business logic
                .build(); // business logic
    } // block end

    private JsonNode callJson(String prompt, String modelName) { // method definition
        try { // exception logic
            LlmRequest request = LlmRequest.builder() // assignment or init
                    .modelType(ModelType.OPENAI_COMPATIBLE) // business logic
                    .modelName(modelName) // business logic
                    .messages(List.of(LlmMessage.user(prompt))) // business logic
                    .temperature(0.0) // business logic
                    .enablePromptInjectionCheck(false) // business logic
                    .build(); // business logic
            LlmResponse response = llmClientUtil.generate(request); // assignment or init
            if (response == null || response.getContent() == null || response.getContent().isBlank()) { // branch logic
                return null; // return result
            } // block end
            String cleaned = stripCodeFence(response.getContent()); // assignment or init
            return objectMapper.readTree(cleaned); // return result
        } catch (Exception e) { // exception logic
            log.debug("model planner parse failed: {}", e.getMessage()); // business logic
            return null; // return result
        } // block end
    } // block end

    private String selectSmallModel() { // method definition
        if (geminiProperty.getSmall() != null && geminiProperty.getSmall().getModelName() != null) { // branch logic
            return geminiProperty.getSmall().getModelName(); // return result
        } // block end
        if (geminiProperty.getMid() != null && geminiProperty.getMid().getModelName() != null) { // branch logic
            return geminiProperty.getMid().getModelName(); // return result
        } // block end
        return geminiProperty.getBig() != null ? geminiProperty.getBig().getModelName() : null; // return result
    } // block end

    private String selectMidModel() { // method definition
        if (geminiProperty.getMid() != null && geminiProperty.getMid().getModelName() != null) { // branch logic
            return geminiProperty.getMid().getModelName(); // return result
        } // block end
        return selectSmallModel(); // return result
    } // block end

    private String stripCodeFence(String content) { // method definition
        String cleaned = content.trim(); // assignment or init
        if (cleaned.startsWith("```")) { // branch logic
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "") // assignment or init
                    .replaceAll("(?s)```\\s*$", "") // business logic
                    .trim(); // business logic
        } // block end
        return cleaned; // return result
    } // block end

    private String trimForPrompt(String input, int maxChars) { // method definition
        if (input == null) { // branch logic
            return ""; // return result
        } // block end
        String normalized = input.replaceAll("\\s+", " ").trim(); // assignment or init
        if (normalized.length() <= maxChars) { // branch logic
            return normalized; // return result
        } // block end
        return normalized.substring(0, maxChars); // return result
    } // block end

    private String safe(String value) { // method definition
        if (value == null) { // branch logic
            return ""; // return result
        } // block end
        return value.replace("\n", " ").replace("\r", " ").trim(); // return result
    } // block end

    private String blankToDefault(String value, String fallback) { // method definition
        return value == null || value.isBlank() ? fallback : value; // return result
    } // block end

    private String blankToNull(String value) { // method definition
        return value == null || value.isBlank() ? null : value; // return result
    } // block end

    private int clamp(int value, int min, int max) { // method definition
        return Math.min(max, Math.max(min, value)); // return result
    } // block end

    @Value // declare annotation
    @Builder // declare annotation
    public static class QueryPlanDecision { // define class
        String queryType; // business logic
        String rewrittenQuery; // business logic
        RetrievalRoute routeHint; // business logic
        String complexity; // business logic
    } // block end

    @Value // declare annotation
    @Builder // declare annotation
    public static class SourceProcessPlan { // define class
        boolean deduplicate; // business logic
        boolean rerank; // business logic
        boolean compress; // business logic
        int topK; // business logic
        int compressionChars; // business logic
    } // block end

    @Value // declare annotation
    @Builder // declare annotation
    public static class AgentStage { // define class
        String objective; // business logic
        String rewrittenQuery; // business logic
        List<RetrievalSource> sources; // business logic
    } // block end
} // block end
