package org.yilena.luna.rag.adapters;

/**
 * Embedding 适配接口。
 * 通过接口隔离具体实现（HTTP、本地进程、mock 等）。
 */
public interface EmbeddingProvider {
    /**
     * 将文本转为向量字符串（pgvector 可直接使用的 JSON-like 形式）。
     */
    String embedding(String text);
}
