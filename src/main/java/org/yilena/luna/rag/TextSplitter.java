package org.yilena.luna.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分片工具类
 * 引入 LangChain4j 后，使用其内置的递归分片器 (Recursive Document Splitter)
 * 它会优先按段落、句子进行切分，最大程度保留语义完整性
 */
public class TextSplitter {

    /**
     * 按递归策略进行智能分片
     *
     * @param text      原始文本
     * @param chunkSize 每个分片的最大字符数
     * @param overlap   相邻分片之间的重叠字符数，用于保持上下文语义连贯
     * @return 分片后的文本列表
     */
    public static List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        // 使用 LangChain4j 的按字符递归分片器
        dev.langchain4j.data.document.DocumentSplitter splitter = 
                DocumentSplitters.recursive(chunkSize, overlap);
        
        List<TextSegment> segments = splitter.split(Document.from(text));
        for (TextSegment segment : segments) {
            chunks.add(segment.text());
        }

        return chunks;
    }
}
