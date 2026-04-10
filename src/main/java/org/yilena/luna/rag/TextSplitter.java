package org.yilena.luna.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 该工具类负责基于 LangChain4j 的递归分片器拆分长文本，尽量在控制块大小的同时保留语义完整性。
 */
public class TextSplitter {

    /**
     * 按递归策略智能切分文本，优先按段落和句子边界拆分，并保留相邻分片的重叠内容。
     */
    public static List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        /**
         * 先创建递归分片器，再按配置切分文档，保证不同长度文本都能统一处理。
         */
        dev.langchain4j.data.document.DocumentSplitter splitter =
                DocumentSplitters.recursive(chunkSize, overlap);

        List<TextSegment> segments = splitter.split(Document.from(text));
        for (TextSegment segment : segments) {
            chunks.add(segment.text());
        }

        return chunks;
    }
}
