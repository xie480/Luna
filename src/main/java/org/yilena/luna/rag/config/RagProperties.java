package org.yilena.luna.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "luna.rag")
/**
 * RAG 模块配置。
 * 支持不同 pipeline 的 top-k、关键词路由规则、压缩长度等参数化。
 */
public class RagProperties {
    // 默认 RAG 超时（给上层兜底/观测使用）
    private long defaultTimeoutMs = 2500;
    // 证据压缩时每条内容最大长度
    private int compressionMaxChars = 500;

    // Search 路由下各 source 默认 top-k
    private Map<RetrievalSource, Integer> searchTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 3,
            RetrievalSource.MEMORY, 3,
            RetrievalSource.PREFERENCE, 2
    );

    // Native 路由下各 source 默认 top-k
    private Map<RetrievalSource, Integer> nativeTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 5,
            RetrievalSource.MEMORY, 5,
            RetrievalSource.PREFERENCE, 3
    );

    // Modular 路由下各 source 默认 top-k
    private Map<RetrievalSource, Integer> modularTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 8,
            RetrievalSource.MEMORY, 6,
            RetrievalSource.PREFERENCE, 3
    );

    // Agentic 路由下各 source 默认 top-k
    private Map<RetrievalSource, Integer> agenticTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 8,
            RetrievalSource.MEMORY, 6,
            RetrievalSource.PREFERENCE, 3
    );

    // 精准查询关键词（优先触发 search）
    private List<String> preciseKeywords = List.of("有没有", "哪条", "那个", "上次", "设置", "记录过", "那条");
    // 分析类关键词（优先触发 agentic）
    private List<String> analysisKeywords = List.of("分析", "比较", "总结", "变化", "原因", "规律", "为什么", "梳理");

    // Agentic 运行限制
    private int agenticMaxSteps = 4;
    private int agenticMaxCalls = 6;
    private int agenticMaxTotalTopK = 24;
    private int agenticMinEvidence = 4;

    // 检索压缩策略
    private int compressionSummarySentences = 2;
    private int compressionMergeSimilarityChars = 80;
}
