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
    private static final Pattern KEY_FACT_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}|\\b\\d+(?:\\.\\d+)?\\b|must|deadline|risk|status|pending|" + Lexicon.KEY_FACT_CHINESE_PATTERN + ")",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FORBIDDEN_DROP_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}|\\b\\d+(?:\\.\\d+)?\\b|pending|unresolved|issue|latest\\s*tool\\s*conclusion|constraint|time|截止|时间|未决|工具结论)",
            Pattern.CASE_INSENSITIVE
    );

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
        List<String> consistencyViolations = enforceConstraintConsistency(input, normalized);
        recalculateTokenCounts(normalized, tokenCounts);

        int total = tokenCounts.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : tokenCounts.entrySet()) {
            ratios.put(entry.getKey(), total <= 0 ? 0.0 : (double) entry.getValue() / total);
        }

        return PruneResult.builder()
                .sections(normalized)
                .sectionTokenCounts(tokenCounts)
                .sectionTokenRatios(ratios)
                .consistencyViolations(consistencyViolations)
                .build();
    }

    private List<String> enforceConstraintConsistency(Map<String, List<String>> original,
                                                      Map<String, List<String>> pruned) {
        List<String> violations = new ArrayList<>();
        for (String sectionName : MUST_KEEP) {
            List<String> source = original.getOrDefault(sectionName, List.of());
            if (source.isEmpty()) {
                continue;
            }
            List<String> target = new ArrayList<>(pruned.getOrDefault(sectionName, List.of()));
            String targetText = String.join(" ", target).toLowerCase();
            List<String> criticalFacts = extractCriticalFacts(source);
            for (String fact : criticalFacts) {
                String normalizedFact = fact.toLowerCase();
                if (normalizedFact.isBlank() || targetText.contains(normalizedFact)) {
                    continue;
                }
                String rescue = preserveCriticalClauses(fact, 420);
                if (!rescue.isBlank()) {
                    target.add(rescue);
                    targetText = (targetText + " " + rescue.toLowerCase()).trim();
                    violations.add(sectionName + ":restored:" + fact);
                } else {
                    violations.add(sectionName + ":missing:" + fact);
                }
            }
            pruned.put(sectionName, target.stream().distinct().toList());
        }
        enforceGlobalForbiddenFacts(original, pruned, violations);
        return violations;
    }

    private void enforceGlobalForbiddenFacts(Map<String, List<String>> original,
                                             Map<String, List<String>> pruned,
                                             List<String> violations) {
        List<String> forbiddenFacts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : original.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            for (String line : entry.getValue()) {
                String normalized = line == null ? "" : line.trim();
                if (normalized.isBlank()) {
                    continue;
                }
                if (FORBIDDEN_DROP_PATTERN.matcher(normalized).find()) {
                    forbiddenFacts.add(normalized);
                }
            }
        }
        if (forbiddenFacts.isEmpty()) {
            return;
        }
        String allPrunedText = pruned.values().stream()
                .flatMap(List::stream)
                .map(item -> item == null ? "" : item.toLowerCase())
                .collect(Collectors.joining(" "));
        List<String> targetSection = new ArrayList<>(pruned.getOrDefault("Current Task State", List.of()));
        for (String fact : forbiddenFacts.stream().distinct().toList()) {
            String normalizedFact = fact.toLowerCase();
            if (normalizedFact.isBlank() || allPrunedText.contains(normalizedFact)) {
                continue;
            }
            targetSection.add(preserveCriticalClauses(fact, 420));
            allPrunedText = (allPrunedText + " " + normalizedFact).trim();
            violations.add("global:restored:" + fact);
        }
        if (!targetSection.isEmpty()) {
            pruned.put("Current Task State", targetSection.stream().distinct().toList());
        }
    }

    private List<String> extractCriticalFacts(List<String> sourceLines) {
        if (sourceLines == null || sourceLines.isEmpty()) {
            return List.of();
        }
        List<String> critical = new ArrayList<>();
        for (String line : sourceLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (line.contains("=") || line.contains(":") || KEY_FACT_PATTERN.matcher(line).find()) {
                critical.add(line.trim());
            }
        }
        if (critical.isEmpty()) {
            return sourceLines.stream().filter(item -> item != null && !item.isBlank()).limit(6).toList();
        }
        return critical.stream().distinct().toList();
    }

    private void recalculateTokenCounts(Map<String, List<String>> sections, Map<String, Integer> tokenCounts) {
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            tokenCounts.put(entry.getKey(), estimateTokens(entry.getValue()));
        }
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
        return mustKeep ? preserveCriticalClauses(normalized, 420) : semanticFallback(normalized);
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
            merged.add(String.join(" | ", group.stream().distinct().toList()));
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
        List<String> consistencyViolations;
    }
}
