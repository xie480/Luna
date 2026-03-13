package org.yilena.luna.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分片工具類
 * 用於將長文本切分為適合向量化和 LLM 上下文的短片段 (Chunk)
 */
public class TextSplitter {

    /**
     * 按固定長度滑動窗口進行分片
     *
     * @param text      原始文本
     * @param chunkSize 每個分片的最大字符數
     * @param overlap   相鄰分片之間的重疊字符數，用於保持上下文語義連貫
     * @return 分片後的文本列表
     */
    public static List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        int start = 0;
        int textLength = text.length();

        // 如果文本本身小於 chunkSize，直接返回
        if (textLength <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);
            chunks.add(text.substring(start, end));

            if (end == textLength) {
                break;
            }
            // 滑動窗口，保留 overlap 長度的重疊
            start = end - overlap;
            // 防止死循環（如果 overlap >= chunkSize）
            if (start >= end) {
                start = end; 
            }
        }

        return chunks;
    }
}
