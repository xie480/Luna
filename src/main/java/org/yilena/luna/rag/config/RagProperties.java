package org.yilena.luna.rag.config;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.RetrievalSource;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

    private Map<String, List<String>> memoryTypeKeywords = Map.of(
            "0", List.of("事实", "客观事实", "事实记忆"),
            "1", List.of("偏好记忆", "偏好", "习惯"),
            "2", List.of("摘要", "总结", "阶段总结"),
            "3", List.of("反思", "复盘", "感悟"),
            "DECISION", List.of("决策", "决定", "选择"),
            "SUCCESS", List.of("成功", "做到了"),
            "FAILURE", List.of("失败", "没做到"),
            "PARTIAL", List.of("部分完成", "半完成"),
            "RULE", List.of("规则记忆", "规则")
    );

    private int agenticMaxSteps = 4;
    private int agenticMaxCalls = 6;
    private int agenticMaxTotalTopK = 24;
    private int agenticMinEvidence = 4;

    private int compressionSummarySentences = 2;
    private int compressionMergeSimilarityChars = 80;

    /**
     * 检索改写模板。必须包含一个 %s 占位符用于拼接原始 query。
     */
    private String analysisRewriteTemplate = "请围绕问题进行结构化检索与分析：%s";
    private String multiSourceRewriteTemplate = "请执行多源联合检索并对齐证据：%s";

    @PostConstruct
    public void sanitizeConfiguredKeywords() {
        preciseKeywords = sanitizeList(preciseKeywords);
        analysisKeywords = sanitizeList(analysisKeywords);
        rewriteKeywords = sanitizeList(rewriteKeywords);
        recencyKeywords = sanitizeList(recencyKeywords);
        referenceKeywords = sanitizeList(referenceKeywords);
        multiSourceKeywords = sanitizeList(multiSourceKeywords);

        sourceKeywords = sanitizeMapList(sourceKeywords);
        preferenceKeyAliases = sanitizeStringMap(preferenceKeyAliases);
        knowledgeSourceTypeKeywords = sanitizeIntegerMapList(knowledgeSourceTypeKeywords);
        memoryTypeKeywords = sanitizeMapList(memoryTypeKeywords);

        analysisRewriteTemplate = sanitizeRewriteTemplate(analysisRewriteTemplate, "请围绕问题进行结构化检索与分析：%s");
        multiSourceRewriteTemplate = sanitizeRewriteTemplate(multiSourceRewriteTemplate, "请执行多源联合检索并对齐证据：%s");
    }

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

    private List<String> sanitizeList(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        return input.stream()
                .map(this::sanitizeKeyword)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, List<String>> sanitizeMapList(Map<String, List<String>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            String key = sanitizeKeyword(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            List<String> values = sanitizeList(entry.getValue());
            if (!values.isEmpty()) {
                sanitized.put(key, values);
            }
        }
        return sanitized;
    }

    private Map<String, String> sanitizeStringMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return input.entrySet().stream()
                .map(entry -> Map.entry(sanitizeKeyword(entry.getKey()), sanitizeKeyword(entry.getValue())))
                .filter(entry -> !entry.getKey().isBlank() && !entry.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Integer, List<String>> sanitizeIntegerMapList(Map<Integer, List<String>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<String>> sanitized = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<String> values = sanitizeList(entry.getValue());
            if (!values.isEmpty()) {
                sanitized.put(entry.getKey(), values);
            }
        }
        return sanitized;
    }

    private String sanitizeKeyword(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .replace("\uFFFD", "")
                .replaceAll("\\p{Cntrl}", "")
                .trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return Objects.equals(normalized, "�") ? "" : normalized;
    }

    private String sanitizeRewriteTemplate(String template, String fallback) {
        String normalized = sanitizeKeyword(template);
        if (normalized.isBlank() || !normalized.contains("%s")) {
            return fallback;
        }
        return normalized;
    }

    public String rewriteWithTemplate(String queryType, String normalizedQuery) {
        String safeQuery = normalizedQuery == null ? "" : normalizedQuery;
        if ("analysis_reasoning".equalsIgnoreCase(queryType)) {
            return analysisRewriteTemplate.formatted(safeQuery);
        }
        if ("multi_source_reasoning".equalsIgnoreCase(queryType)) {
            return multiSourceRewriteTemplate.formatted(safeQuery);
        }
        return safeQuery;
    }
}
