package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 该重排器负责调用模型重排接口对候选证据重新排序，并把排序结果回写为统一分数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceReranker {

    /**
     * 统一 LLM 调用工具。
     */
    private final LlmClientUtil llmClientUtil;

    /**
     * 基于查询对候选证据执行重排，失败时回退到原始顺序。
     */
    public List<Evidence> rerank(String query, List<Evidence> evidences, int topK) {
        if (evidences == null || evidences.isEmpty()) {
            return Collections.emptyList();
        }
        List<Evidence> source = evidences.size() > topK ? evidences.subList(0, topK) : evidences;
        List<String> docs = source.stream().map(Evidence::getContent).toList();
        try {
            List<Double> scores = llmClientUtil.rerank(query, docs);
            List<Evidence> ranked = llmClientUtil.rerankResources(source, scores, topK);
            return IntStream.range(0, ranked.size())
                    .mapToObj(i -> ranked.get(i).toBuilder().score(scoreByRank(i, ranked.size())).build())
                    .toList();
        } catch (Exception e) {
            log.warn("RAG rerank 失败，使用原顺序返回: {}", e.getMessage());
            return source.stream().limit(topK).toList();
        }
    }

    /**
     * 根据排序名次生成递减分数，便于后续统一比较。
     */
    private double scoreByRank(int index, int total) {
        if (total <= 1) {
            return 1.0D;
        }
        return 1.0D - ((double) index / (double) total);
    }
}
