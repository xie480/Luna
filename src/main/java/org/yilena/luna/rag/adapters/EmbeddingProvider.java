package org.yilena.luna.rag.adapters;

/**
 * 该适配接口用于隔离具体的 Embedding 生成实现，便于在 HTTP、本地进程或 mock 方案之间切换。
 */
public interface EmbeddingProvider {

    /**
     * 将文本转换为向量字符串，返回可直接供 pgvector 等存储或检索组件使用的表示形式。
     */
    String embedding(String text);
}
