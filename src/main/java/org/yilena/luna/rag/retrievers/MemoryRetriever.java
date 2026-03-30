package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 记忆检索器，负责会话记忆向量召回并支持 session 过滤。 */
@Component
@RequiredArgsConstructor
public class MemoryRetriever implements BaseRetriever {

    private final PgRetrievalAdapter pgRetrievalAdapter; // 声明成员字段

    @Override // 声明注解
    public RetrievalSource source() { // 定义方法签名
        return RetrievalSource.MEMORY; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) { // 定义方法签名
        String vector = queryObject.getEmbedding(); // 执行赋值操作
        if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) { // 进行条件判断
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
        List<Memory> records = pgRetrievalAdapter.searchMemoryByVector(vector, queryObject.getSessionId(), topK); // 执行赋值操作
        if (records == null || records.isEmpty()) { // 进行条件判断
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
        return IntStream.range(0, records.size()) // 返回处理结果
                .mapToObj(index -> toEvidence(records.get(index), index, records.size())) // 执行当前逻辑
                .toList(); // 执行语句逻辑
    } // 结束当前代码块

    private Evidence toEvidence(Memory memory, int index, int total) { // 定义方法签名
        Map<String, Object> metadata = new HashMap<>(); // 执行赋值操作
        metadata.put("raw_id", memory.getId()); // 执行语句逻辑
        metadata.put("memory_type", memory.getMemoryType() != null ? memory.getMemoryType().getValue() : null); // 执行赋值操作
        metadata.put("weight", memory.getWeight()); // 执行语句逻辑
        metadata.put("session_id", memory.getSessionId()); // 执行语句逻辑
        return Evidence.builder() // 返回处理结果
                .id("memory:" + memory.getId()) // 执行当前逻辑
                .source(RetrievalSource.MEMORY) // 执行当前逻辑
                .type("memory") // 执行当前逻辑
                .title(null) // 执行当前逻辑
                .content(memory.getContent()) // 执行当前逻辑
                .score(rankScore(index, total)) // 执行当前逻辑
                .metadata(metadata) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private double rankScore(int index, int total) { // 定义方法签名
        if (total <= 1) { // 进行条件判断
            return 1.0D; // 返回处理结果
        } // 结束当前代码块
        return 1.0D - ((double) index / (double) total); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
