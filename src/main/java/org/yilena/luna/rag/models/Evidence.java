package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.Map;

@Value
@Builder(toBuilder = true)
/**
 * 标准化证据对象。
 * 用于屏蔽底层表结构差异，统一供上层消费。
 */
public class Evidence {
    // 统一 ID（如 knowledge:101）
    String id;
    // 证据来源
    RetrievalSource source;
    // 证据类型字符串（knowledge/memory/preference）
    String type;
    // 标题（可空）
    String title;
    // 证据文本内容
    String content;
    // 排序分数（当前为 pipeline 内部排序分）
    double score;

    // source 特定元数据
    @Builder.Default
    Map<String, Object> metadata = Collections.emptyMap();
}
