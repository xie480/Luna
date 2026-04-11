package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
/**
 * 该流水线面向模块化多源检索场景，支持查询改写、来源路由和跨源融合后的角色分组输出。
 */
public class ModularPipeline extends AbstractRetrievalPipeline {

    public ModularPipeline(
            List<BaseRetriever> retrievers,
            EvidenceReranker evidenceReranker,
            EvidenceDeduplicator evidenceDeduplicator,
            EvidenceCompressor evidenceCompressor,
            RagProperties ragProperties,
            ModelDrivenRagPlanner modelDrivenRagPlanner,
            EvidenceFusionService evidenceFusionService
    ) {
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)),
                evidenceReranker, evidenceDeduplicator, evidenceCompressor,
                ragProperties, modelDrivenRagPlanner, evidenceFusionService);
    }

    @Override
    public RetrievalRoute route() {
        return RetrievalRoute.MODULAR;
    }

    /**
     * 先按需要改写查询并细化来源路由，再执行多源检索和跨源融合。
     */
    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        /**
         * 先根据规划判断是否需要补做改写，避免原始查询对多源检索不够友好。
         */
        QueryObject effectiveQuery = applyConditionalRewrite(queryObject, plan);
        List<RetrievalSource> routedSources = routeSources(effectiveQuery, plan, request);
        /**
         * 再按路由后的来源执行通用检索流程，并开启跨源融合与压缩。
         */
        SourceRetrieveOutcome outcome = retrieveBySources(
                effectiveQuery,
                plan.getTopKConfig(),
                routedSources,
                plan.isNeedsRerank(),
                true,
                true,
                request,
                resolveTimeoutMs(request)
        );
        /**
         * 最后补充来源路由和角色分组结果，方便上层按证据语义角色消费。
         */
        Map<String, Object> meta = new HashMap<>(outcome.meta());
        Map<RetrievalSource, List<Evidence>> grouped =
                ensureAllEvidenceBuckets(outcome.grouped());
        Map<EvidenceRole, List<Evidence>> roleGroups =
                buildEvidenceRoleGroups(grouped);
        meta.put("source_routing", routedSources.stream().map(RetrievalSource::value).toList());
        meta.put("rewrite_applied", plan.isNeedsRewrite());
        meta.put("evidence_role_grouping", true);
        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(effectiveQuery.getRewrittenQuery())
                .evidences(grouped)
                .evidenceRoleGroups(roleGroups)
                .meta(meta)
                .build();
    }

    private QueryObject applyConditionalRewrite(QueryObject queryObject, RoutePlan plan) {
        String normalized = queryObject.getNormalizedQuery() == null || queryObject.getNormalizedQuery().isBlank()
                ? queryObject.getOriginalQuery()
                : queryObject.getNormalizedQuery();
        if (plan != null && plan.isNeedsRewrite()) {
            String rewritten = queryObject.getRewrittenQuery();
            if (rewritten != null && !rewritten.isBlank() && !rewritten.equals(normalized)) {
                return queryObject;
            }
            return queryObject.toBuilder()
                    .rewrittenQuery(synthesizeRewrite(normalized, plan.getQueryType(), queryObject.getQueryType()))
                    .build();
        }
        return queryObject.toBuilder().rewrittenQuery(normalized).build();
    }

    private String synthesizeRewrite(String normalized, String planQueryType, String queryType) {
        String effectiveType = planQueryType == null || planQueryType.isBlank() ? queryType : planQueryType;
        return getRagProperties().rewriteWithTemplate(effectiveType, normalizeText(normalized));
    }

    private List<RetrievalSource> routeSources(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        List<RetrievalSource> base = plan.getSources() == null || plan.getSources().isEmpty()
                ? resolveSources(request)
                : new ArrayList<>(plan.getSources());
        List<RetrievalSource> inferred = inferredSourcesFromQuery(queryObject);
        if (inferred.isEmpty()) {
            return base;
        }
        List<RetrievalSource> routed = inferred.stream().filter(base::contains).toList();
        return routed.isEmpty() ? base : routed;
    }

    private List<RetrievalSource> inferredSourcesFromQuery(QueryObject queryObject) {
        if (queryObject.getPossibleFilters() != null && queryObject.getPossibleFilters().get("inferred_sources") instanceof List<?> raw) {
            List<RetrievalSource> parsed = raw.stream()
                    .map(String::valueOf)
                    .map(RetrievalSource::fromValue)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .distinct()
                    .toList();
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }

        String query = queryObject.getRewrittenQuery() == null ? "" : queryObject.getRewrittenQuery();
        Set<RetrievalSource> inferred = new LinkedHashSet<>();
        for (Map.Entry<RetrievalSource, List<String>> entry : getRagProperties().sourceKeywordMap().entrySet()) {
            if (containsAny(query, entry.getValue())) {
                inferred.add(entry.getKey());
            }
        }
        if (containsAny(query, getRagProperties().getMultiSourceKeywords())) {
            inferred.addAll(List.of(RetrievalSource.values()));
        }
        return new ArrayList<>(inferred);
    }

    private boolean containsAny(String query, List<String> keywords) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalizedQuery = normalizeText(query);
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeText(keyword);
            if (!normalizedKeyword.isBlank() && normalizedQuery.contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .replace("\uFFFD", "")
                .trim();
    }
}
