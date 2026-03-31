package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcRelationalMemoryRetriever implements RelationalMemoryRetriever {

    private final RuntimeReadMapper runtimeReadMapper;
    private final LlmClientUtil llmClientUtil;

    @Override
    public Map<String, Object> retrieve(String sessionId, String userInput) {
        Map<String, Object> result = new HashMap<>();
        String queryVector = queryVector(userInput);
        result.put("working_memory", queryOne(() -> runtimeReadMapper.selectRelationalWorkingMemory(sessionId)));
        result.put("profile", queryOne(() -> runtimeReadMapper.selectRelationalProfile(sessionId)));
        result.put("semantic_facts", queryList(() -> runtimeReadMapper.selectRelationalSemanticFacts(sessionId, queryVector)));
        result.put("episodes", queryList(() -> runtimeReadMapper.selectRelationalEpisodes(sessionId, queryVector)));
        result.put("procedures", queryList(() -> runtimeReadMapper.selectRelationalProcedures(queryVector)));
        result.put("emotional_baseline", queryOne(() -> runtimeReadMapper.selectEmotionalBaseline(sessionId)));
        result.put("boundary_rules", queryList(() -> runtimeReadMapper.selectBoundaryRules(sessionId)));
        return result;
    }

    private String queryVector(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }
        try {
            return llmClientUtil.getEmbedding(userInput);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Map<String, Object> queryOne(SqlOneSupplier supplier) {
        try {
            Map<String, Object> row = supplier.get();
            return row == null ? Collections.emptyMap() : row;
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> queryList(SqlListSupplier supplier) {
        try {
            List<Map<String, Object>> rows = supplier.get();
            return rows == null ? Collections.emptyList() : rows;
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    @FunctionalInterface
    private interface SqlOneSupplier {
        Map<String, Object> get();
    }

    @FunctionalInterface
    private interface SqlListSupplier {
        List<Map<String, Object>> get();
    }
}
