package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Modular 检索流水线，按请求指定的数据源并行召回并统一执行去重、重排与压缩。 */
@Component
public class ModularPipeline extends AbstractRetrievalPipeline {

    public ModularPipeline( // 定义方法签名
            List<BaseRetriever> retrievers, // 执行当前逻辑
            EvidenceReranker evidenceReranker, // 执行当前逻辑
            EvidenceDeduplicator evidenceDeduplicator, // 执行当前逻辑
            EvidenceCompressor evidenceCompressor, // 执行当前逻辑
            RagProperties ragProperties, // 执行当前逻辑
            ModelDrivenRagPlanner modelDrivenRagPlanner, // 执行当前逻辑
            EvidenceFusionService evidenceFusionService // 执行当前逻辑
    ) { // 开始新的代码块
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)), // 执行当前逻辑
                evidenceReranker, evidenceDeduplicator, evidenceCompressor, // 执行当前逻辑
                ragProperties, modelDrivenRagPlanner, evidenceFusionService); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public RetrievalRoute route() { // 定义方法签名
        return RetrievalRoute.MODULAR; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) { // 定义方法签名
        SourceRetrieveOutcome outcome = retrieveBySources( // 执行赋值操作
                queryObject, // 执行当前逻辑
                plan.getTopKConfig(), // 执行当前逻辑
                resolveSources(request), // 执行当前逻辑
                true, // 执行当前逻辑
                true, // 执行当前逻辑
                request, // 执行当前逻辑
                resolveTimeoutMs(request) // 执行当前逻辑
        ); // 执行语句逻辑
        Map<String, Object> meta = new HashMap<>(outcome.meta()); // 执行赋值操作
        return RetrievalResponse.builder() // 返回处理结果
                .route(route()) // 执行当前逻辑
                .rewrittenQuery(queryObject.getRewrittenQuery()) // 执行当前逻辑
                .evidences(outcome.grouped()) // 执行当前逻辑
                .meta(meta) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
