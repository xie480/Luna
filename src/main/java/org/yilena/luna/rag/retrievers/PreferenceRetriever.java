package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 偏好检索器，负责用户偏好向量召回并构建可消费证据。 */
@Component
@RequiredArgsConstructor
public class PreferenceRetriever implements BaseRetriever {

    private final PgRetrievalAdapter pgRetrievalAdapter; // 声明成员字段

    @Override // 声明注解
    public RetrievalSource source() { // 定义方法签名
        return RetrievalSource.PREFERENCE; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) { // 定义方法签名
        String vector = queryObject.getEmbedding(); // 执行赋值操作
        if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) { // 进行条件判断
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
        List<UserPreference> records = pgRetrievalAdapter.searchPreferenceByVector(vector, topK); // 执行赋值操作
        if (records == null || records.isEmpty()) { // 进行条件判断
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
        return IntStream.range(0, records.size()) // 返回处理结果
                .mapToObj(index -> toEvidence(records.get(index), index, records.size())) // 执行当前逻辑
                .toList(); // 执行语句逻辑
    } // 结束当前代码块

    private Evidence toEvidence(UserPreference preference, int index, int total) { // 定义方法签名
        Map<String, Object> metadata = new HashMap<>(); // 执行赋值操作
        metadata.put("raw_id", preference.getId()); // 执行语句逻辑
        metadata.put("pref_key", preference.getPrefKey()); // 执行语句逻辑
        metadata.put("pref_value", preference.getPrefValue()); // 执行语句逻辑
        return Evidence.builder() // 返回处理结果
                .id("preference:" + preference.getId()) // 执行当前逻辑
                .source(RetrievalSource.PREFERENCE) // 执行当前逻辑
                .type("preference") // 执行当前逻辑
                .title(preference.getPrefKey()) // 执行当前逻辑
                .content(buildContent(preference)) // 执行当前逻辑
                .score(rankScore(index, total)) // 执行当前逻辑
                .metadata(metadata) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private String buildContent(UserPreference preference) { // 定义方法签名
        return "pref_key=" + nullSafe(preference.getPrefKey()) // 返回处理结果
                + ", pref_value=" + nullSafe(preference.getPrefValue()) // 执行赋值操作
                + ", description=" + nullSafe(preference.getDescription()); // 执行赋值操作
    } // 结束当前代码块

    private String nullSafe(String value) { // 定义方法签名
        return value == null ? "" : value; // 返回处理结果
    } // 结束当前代码块

    private double rankScore(int index, int total) { // 定义方法签名
        if (total <= 1) { // 进行条件判断
            return 1.0D; // 返回处理结果
        } // 结束当前代码块
        return 1.0D - ((double) index / (double) total); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
