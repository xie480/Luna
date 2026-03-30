package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;

import java.util.List;

/** Evidence compressor with configurable max chars. */
@Component
@RequiredArgsConstructor
public class EvidenceCompressor {

    private final RagProperties ragProperties; // 声明成员字段

    public List<Evidence> compress(List<Evidence> evidences) { // 定义方法签名
        return compress(evidences, ragProperties.getCompressionMaxChars()); // 返回处理结果
    } // 结束当前代码块

    public List<Evidence> compress(List<Evidence> evidences, int maxChars) { // 定义方法签名
        return evidences.stream() // 返回处理结果
                .map(item -> item.toBuilder().content(truncate(item.getContent(), maxChars)).build()) // 执行当前逻辑
                .toList(); // 执行语句逻辑
    } // 结束当前代码块

    private String truncate(String content, int maxChars) { // 定义方法签名
        if (content == null || content.length() <= maxChars) { // 进行条件判断
            return content; // 返回处理结果
        } // 结束当前代码块
        return content.substring(0, maxChars) + "..."; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
