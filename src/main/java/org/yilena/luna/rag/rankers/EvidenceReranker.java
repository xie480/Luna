package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/** 证据重排器，调用模型重排接口对候选证据重新排序。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceReranker {

    private final LlmClientUtil llmClientUtil; // 声明成员字段

    public List<Evidence> rerank(String query, List<Evidence> evidences, int topK) { // 定义方法签名
        if (evidences == null || evidences.isEmpty()) { // 进行条件判断
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
        List<Evidence> source = evidences.size() > topK ? evidences.subList(0, topK) : evidences; // 执行赋值操作
        List<String> docs = source.stream().map(Evidence::getContent).toList(); // 执行赋值操作
        try { // 尝试执行核心逻辑
            List<Double> scores = llmClientUtil.rerank(query, docs); // 执行赋值操作
            List<Evidence> ranked = llmClientUtil.rerankResources(source, scores, topK); // 执行赋值操作
            return IntStream.range(0, ranked.size()) // 返回处理结果
                    .mapToObj(i -> ranked.get(i).toBuilder().score(scoreByRank(i, ranked.size())).build()) // 执行当前逻辑
                    .toList(); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.warn("RAG rerank 失败，使用原顺序返回: {}", e.getMessage()); // 执行语句逻辑
            return source.stream().limit(topK).toList(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private double scoreByRank(int index, int total) { // 定义方法签名
        if (total <= 1) { // 进行条件判断
            return 1.0D; // 返回处理结果
        } // 结束当前代码块
        return 1.0D - ((double) index / (double) total); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
