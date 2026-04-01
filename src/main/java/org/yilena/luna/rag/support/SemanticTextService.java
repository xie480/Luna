package org.yilena.luna.rag.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.EmbeddingProvider;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SemanticTextService {

    private final EmbeddingProvider embeddingProvider;

    public double similarity(String left, String right, Map<String, List<Double>> embeddingCache) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return 0.0;
        }
        if (normalizedLeft.equals(normalizedRight)) {
            return 1.0;
        }

        double lexical = lexicalSimilarity(normalizedLeft, normalizedRight);
        List<Double> leftEmbedding = embeddingOf(normalizedLeft, embeddingCache);
        List<Double> rightEmbedding = embeddingOf(normalizedRight, embeddingCache);
        if (!leftEmbedding.isEmpty() && !rightEmbedding.isEmpty()) {
            double cosine = cosine(leftEmbedding, rightEmbedding);
            if (!Double.isNaN(cosine) && !Double.isInfinite(cosine)) {
                return clamp01(0.78 * cosine + 0.22 * lexical);
            }
        }
        return lexical;
    }

    public List<String> splitSentences(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String[] pieces = content.split("(?<=[。！？!?；;\\.])");
        List<String> sentences = new ArrayList<>();
        for (String piece : pieces) {
            String sentence = piece == null ? "" : piece.trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        if (!sentences.isEmpty()) {
            return sentences;
        }
        return List.of(content.trim());
    }

    public String summarizeBySemantic(
            String content,
            int sentenceCount,
            int maxChars,
            Map<String, List<Double>> embeddingCache
    ) {
        if (content == null || content.isBlank()) {
            return content;
        }
        List<String> sentences = splitSentences(content);
        if (sentences.isEmpty()) {
            return content;
        }
        int keep = Math.max(1, sentenceCount);
        if (sentences.size() <= keep) {
            return joinWithBudget(sentences, maxChars);
        }

        List<SentenceScore> scored = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            double relevance = similarity(sentence, content, embeddingCache);
            double info = Math.min(1.0, Math.sqrt(Math.max(1, sentence.length())) / 12.0);
            scored.add(new SentenceScore(i, sentence, 0.85 * relevance + 0.15 * info));
        }

        List<SentenceScore> selected = selectByMmr(scored, keep, embeddingCache);
        selected.sort(java.util.Comparator.comparingInt(SentenceScore::index));
        List<String> ordered = selected.stream().map(SentenceScore::sentence).toList();
        return joinWithBudget(ordered, maxChars);
    }

    private List<SentenceScore> selectByMmr(
            List<SentenceScore> scored,
            int keep,
            Map<String, List<Double>> embeddingCache
    ) {
        List<SentenceScore> selected = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        while (selected.size() < keep && selected.size() < scored.size()) {
            SentenceScore best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (SentenceScore candidate : scored) {
                if (used.contains(candidate.index())) {
                    continue;
                }
                double redundancy = 0.0;
                for (SentenceScore picked : selected) {
                    redundancy = Math.max(redundancy, similarity(candidate.sentence(), picked.sentence(), embeddingCache));
                }
                double mmrScore = 0.75 * candidate.score() - 0.25 * redundancy;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            used.add(best.index());
            selected.add(best);
        }
        return selected;
    }

    private String joinWithBudget(List<String> sentences, int maxChars) {
        if (sentences == null || sentences.isEmpty()) {
            return "";
        }
        int budget = Math.max(32, maxChars);
        StringBuilder builder = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence == null || sentence.isBlank()) {
                continue;
            }
            String unit = sentence.trim();
            int extra = builder.isEmpty() ? unit.length() : unit.length() + 1;
            if (!builder.isEmpty() && builder.length() + extra > budget) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(unit);
        }
        if (!builder.isEmpty()) {
            return builder.toString();
        }
        return truncate(sentences.getFirst(), budget);
    }

    private List<Double> embeddingOf(String text, Map<String, List<Double>> cache) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (cache != null && cache.containsKey(text)) {
            return cache.get(text);
        }
        String raw = embeddingProvider.embedding(limitForEmbedding(text));
        List<Double> parsed = parseEmbedding(raw);
        List<Double> value = parsed.isEmpty() ? List.of() : parsed;
        if (cache != null) {
            cache.put(text, value);
        }
        return value;
    }

    private List<Double> parseEmbedding(String rawEmbedding) {
        if (rawEmbedding == null || rawEmbedding.isBlank()) {
            return List.of();
        }
        String cleaned = rawEmbedding.trim();
        if (cleaned.startsWith("[")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(cleaned.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Double::parseDouble)
                    .toList();
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        int dims = Math.min(left.size(), right.size());
        if (dims == 0) {
            return Double.NaN;
        }
        double dot = 0.0;
        double normLeft = 0.0;
        double normRight = 0.0;
        for (int i = 0; i < dims; i++) {
            double lv = left.get(i);
            double rv = right.get(i);
            dot += lv * rv;
            normLeft += lv * lv;
            normRight += rv * rv;
        }
        if (normLeft <= 0.0 || normRight <= 0.0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(normLeft) * Math.sqrt(normRight));
    }

    private double lexicalSimilarity(String left, String right) {
        Set<String> leftNgram = ngrams(left, 2);
        Set<String> rightNgram = ngrams(right, 2);
        if (leftNgram.isEmpty() || rightNgram.isEmpty()) {
            return left.equals(right) ? 1.0 : 0.0;
        }
        Set<String> union = new HashSet<>(leftNgram);
        union.addAll(rightNgram);
        Set<String> intersection = new HashSet<>(leftNgram);
        intersection.retainAll(rightNgram);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) intersection.size() / (double) union.size();
    }

    private Set<String> ngrams(String text, int n) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() <= n) {
            return Set.of(compact);
        }
        Set<String> grams = new HashSet<>();
        for (int i = 0; i <= compact.length() - n; i++) {
            grams.add(compact.substring(i, i + n));
        }
        return grams;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .replace("\uFFFD", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }

    private String limitForEmbedding(String text) {
        int maxChars = 1200;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "...";
    }

    private record SentenceScore(int index, String sentence, double score) {
    }
}
