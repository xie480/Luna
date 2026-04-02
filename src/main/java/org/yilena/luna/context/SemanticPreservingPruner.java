package org.yilena.luna.context;

import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SemanticPreservingPruner {

    private static final List<String> MUST_KEEP = List.of(
            "Instructions",
            "Current Task State",
            "Reconstructed User Intent",
            "Tool Evidence",
            "Output Constraints"
    );
    private static final Pattern KEY_FACT_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}|\\b\\d+(?:\\.\\d+)?\\b|must|必须|不要|deadline|预算|risk|status|pending)", Pattern.CASE_INSENSITIVE);

    public PruneResult prune(Map<String, List<String>> sections, Map<String, Integer> sectionBudget) {
        Map<String, List<String>> input = sections == null ? Map.of() : sections;
        Map<String, Integer> budget = sectionBudget == null ? Map.of() : sectionBudget;

        Map<String, List<String>> normalized = new LinkedHashMap<>();
        Map<String, Integer> tokenCounts = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            String name = entry.getKey();
            List<String> lines = dedupe(entry.getValue());
            int maxTokens = Math.max(40, budget.getOrDefault(name, 1200));
            List<String> compact = compactLines(lines, maxTokens, MUST_KEEP.contains(name));
            normalized.put(name, compact);
            tokenCounts.put(name, estimateTokens(compact));
        }

        int total = tokenCounts.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : tokenCounts.entrySet()) {
            if (total <= 0) {
                ratios.put(entry.getKey(), 0.0);
            } else {
                ratios.put(entry.getKey(), (double) entry.getValue() / total);
            }
        }

        return PruneResult.builder()
                .sections(normalized)
                .sectionTokenCounts(tokenCounts)
                .sectionTokenRatios(ratios)
                .build();
    }

    private List<String> dedupe(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String line : source) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                deduped.add(trimmed);
            }
        }
        return new ArrayList<>(deduped);
    }

    private List<String> compactLines(List<String> lines, int tokenBudget, boolean mustKeep) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int used = 0;
        for (String line : lines) {
            String compacted = compactSentence(line, mustKeep);
            int estimate = estimateTokens(List.of(compacted));
            if (used + estimate <= tokenBudget || (mustKeep && out.isEmpty())) {
                out.add(compacted);
                used += estimate;
                continue;
            }
            if (mustKeep) {
                out.add(semanticFallback(line));
                break;
            }
        }
        if (used > tokenBudget) {
            return semanticClusterMerge(out, tokenBudget, mustKeep);
        }
        return out;
    }

    private String compactSentence(String line, boolean mustKeep) {
        String normalized = line.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 360) {
            return normalized;
        }
        if (!mustKeep) {
            return semanticFallback(normalized);
        }
        return preserveCriticalClauses(normalized, 420);
    }

    private String preserveCriticalClauses(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        List<String> clauses = splitClauses(text);
        List<String> critical = new ArrayList<>();
        for (String clause : clauses) {
            String compact = clause.trim();
            if (compact.isBlank()) {
                continue;
            }
            if (KEY_FACT_PATTERN.matcher(compact).find()) {
                critical.add(compact);
            }
        }
        if (critical.isEmpty()) {
            critical.addAll(clauses);
        }
        return joinClausesWithinLimit(critical, maxLen);
    }

    private String semanticFallback(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> clauses = splitClauses(text);
        List<String> selected = new ArrayList<>();
        for (String clause : clauses) {
            String compact = clause.trim();
            if (compact.isBlank()) {
                continue;
            }
            if (KEY_FACT_PATTERN.matcher(compact).find() || compact.contains("=") || compact.contains(":")) {
                selected.add(compact);
            }
        }
        if (selected.isEmpty()) {
            selected.addAll(clauses);
        }
        return joinClausesWithinLimit(selected, 300);
    }

    private List<String> semanticClusterMerge(List<String> lines, int tokenBudget, boolean mustKeep) {
        Map<String, List<String>> cluster = new LinkedHashMap<>();
        for (String line : lines) {
            String key = semanticKey(line);
            cluster.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
        }
        List<String> merged = new ArrayList<>();
        for (List<String> group : cluster.values()) {
            String one = String.join(" | ", group.stream().distinct().toList());
            merged.add(one);
        }
        List<String> out = new ArrayList<>();
        int used = 0;
        for (String line : merged) {
            int estimate = estimateTokens(List.of(line));
            if (used + estimate > tokenBudget && !out.isEmpty()) {
                break;
            }
            out.add(mustKeep ? preserveCriticalClauses(line, 420) : semanticFallback(line));
            used += estimate;
        }
        return out;
    }

    private String semanticKey(String line) {
        if (line == null || line.isBlank()) {
            return "empty";
        }
        String normalized = line.toLowerCase().trim();
        int idx = normalized.indexOf('=');
        if (idx <= 0) {
            idx = normalized.indexOf(':');
        }
        if (idx > 0) {
            String key = normalized.split("[=:]", 2)[0].trim();
            return key.isBlank() ? "generic" : key;
        }
        String compact = joinWordsWithinLimit(Arrays.asList(normalized.split("\\s+")), 40);
        return compact.isBlank() ? "generic" : compact;
    }

    private int estimateTokens(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int chars = lines.stream().mapToInt(item -> item == null ? 0 : item.length()).sum();
        return Math.max(1, chars / 4);
    }

    private List<String> splitClauses(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.split("[;；\\n]");
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String compact = part.trim();
            if (!compact.isBlank()) {
                clauses.add(compact);
            }
        }
        if (clauses.isEmpty()) {
            clauses.add(text.trim());
        }
        return clauses;
    }

    private String joinClausesWithinLimit(List<String> clauses, int maxLen) {
        if (clauses == null || clauses.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        int used = 0;
        for (String clause : clauses.stream().distinct().toList()) {
            String normalized = clause == null ? "" : clause.trim();
            if (normalized.isBlank()) {
                continue;
            }
            int next = normalized.length() + (out.isEmpty() ? 0 : 3);
            if (used + next <= maxLen) {
                out.add(normalized);
                used += next;
                continue;
            }
            if (out.isEmpty()) {
                out.add(compressClauseWithinLimit(normalized, maxLen));
            }
            break;
        }
        return out.stream().filter(item -> item != null && !item.isBlank()).collect(Collectors.joining(" ; "));
    }

    private String compressClauseWithinLimit(String clause, int maxLen) {
        if (clause == null || clause.isBlank()) {
            return "";
        }
        if (clause.length() <= maxLen) {
            return clause;
        }
        List<String> keyTokens = new ArrayList<>();
        for (String token : clause.split("[\\s,，]+")) {
            String compact = token == null ? "" : token.trim();
            if (compact.isBlank()) {
                continue;
            }
            if (KEY_FACT_PATTERN.matcher(compact).find() || compact.contains("=") || compact.contains(":")) {
                keyTokens.add(compact);
            }
        }
        if (!keyTokens.isEmpty()) {
            String merged = joinWordsWithinLimit(keyTokens, maxLen);
            if (!merged.isBlank()) {
                return merged;
            }
        }
        return joinWordsWithinLimit(Arrays.asList(clause.split("\\s+")), maxLen);
    }

    private String joinWordsWithinLimit(List<String> words, int maxLen) {
        if (words == null || words.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            String compact = word == null ? "" : word.trim();
            if (compact.isBlank()) {
                continue;
            }
            if (sb.length() == 0) {
                if (compact.length() > maxLen) {
                    continue;
                }
                sb.append(compact);
                continue;
            }
            if (sb.length() + 1 + compact.length() > maxLen) {
                break;
            }
            sb.append(' ').append(compact);
        }
        return sb.toString();
    }

    @Value
    @Builder
    public static class PruneResult {
        Map<String, List<String>> sections;
        Map<String, Integer> sectionTokenCounts;
        Map<String, Double> sectionTokenRatios;
    }
}
