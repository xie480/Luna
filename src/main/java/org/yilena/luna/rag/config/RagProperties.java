package org.yilena.luna.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "luna.rag")
public class RagProperties {

    private long defaultTimeoutMs = 2500;
    private int compressionMaxChars = 500;

    private Map<RetrievalSource, Integer> searchTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 3,
            RetrievalSource.MEMORY, 3,
            RetrievalSource.PREFERENCE, 2
    );

    private Map<RetrievalSource, Integer> nativeTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 5,
            RetrievalSource.MEMORY, 5,
            RetrievalSource.PREFERENCE, 3
    );

    private Map<RetrievalSource, Integer> modularTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 8,
            RetrievalSource.MEMORY, 6,
            RetrievalSource.PREFERENCE, 3
    );

    private Map<RetrievalSource, Integer> agenticTopK = Map.of(
            RetrievalSource.KNOWLEDGE, 8,
            RetrievalSource.MEMORY, 6,
            RetrievalSource.PREFERENCE, 3
    );

    private List<String> preciseKeywords = List.of("有没有", "哪条", "那个", "上次", "设置", "记录过", "那条");
    private List<String> analysisKeywords = List.of("分析", "比较", "总结", "变化", "原因", "规律", "为什么", "梳理");

    private List<String> rewriteKeywords = List.of("结合", "根据", "按我的", "分析", "比较", "总结");
    private List<String> recencyKeywords = List.of("最近", "这段时间", "近期", "上周", "本月");
    private List<String> referenceKeywords = List.of(
            "这个", "这个问题", "这个情况", "这件事", "那个", "那件事", "它", "他", "她", "上面", "前面", "刚才", "继续"
    );
    private List<String> multiSourceKeywords = List.of("结合", "根据", "按我的", "综合", "一起");

    private List<RetrievalRouteRule> routePriority = List.of(
            RetrievalRouteRule.SEARCH,
            RetrievalRouteRule.NATIVE,
            RetrievalRouteRule.MODULAR,
            RetrievalRouteRule.AGENTIC
    );

    private List<RetrievalSource> nativePrimarySourcePriority = List.of(
            RetrievalSource.PREFERENCE,
            RetrievalSource.MEMORY,
            RetrievalSource.KNOWLEDGE
    );

    private Map<String, List<String>> sourceKeywords = Map.of(
            RetrievalSource.KNOWLEDGE.value(), List.of("知识", "文档", "资料", "定义", "规则", "是什么", "说明"),
            RetrievalSource.MEMORY.value(), List.of("记忆", "之前", "过去", "历史", "上次", "经历", "情况"),
            RetrievalSource.PREFERENCE.value(), List.of("偏好", "语气", "风格", "称呼", "长度", "习惯")
    );

    private Map<String, String> preferenceKeyAliases = Map.of(
            "回答长度", "response_length",
            "回复长度", "response_length",
            "语气", "tone",
            "风格", "response_style",
            "称呼", "nickname"
    );

    private Map<Integer, List<String>> knowledgeSourceTypeKeywords = Map.of(
            2, List.of("手动", "手输", "我输入", "人工录入"),
            0, List.of("文件", "文档", "本地"),
            1, List.of("网页", "联网", "搜索结果", "web")
    );

    private int agenticMaxSteps = 4;
    private int agenticMaxCalls = 6;
    private int agenticMaxTotalTopK = 24;
    private int agenticMinEvidence = 4;

    private int compressionSummarySentences = 2;
    private int compressionMergeSimilarityChars = 80;

    public enum RetrievalRouteRule {
        SEARCH,
        NATIVE,
        MODULAR,
        AGENTIC
    }

    public List<String> keywordsOf(RetrievalSource source) {
        if (source == null || sourceKeywords == null || sourceKeywords.isEmpty()) {
            return List.of();
        }
        return sourceKeywords.getOrDefault(source.value(), List.of());
    }

    public Map<RetrievalSource, List<String>> sourceKeywordMap() {
        Map<RetrievalSource, List<String>> mapped = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : RetrievalSource.values()) {
            mapped.put(source, keywordsOf(source));
        }
        return mapped;
    }
}
