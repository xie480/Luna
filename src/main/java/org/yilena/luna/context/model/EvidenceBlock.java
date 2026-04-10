package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 该模型用于描述单条可注入上下文的证据块，统一承载来源、内容和打分信息。
 */
@Value
@Builder
public class EvidenceBlock {
    /**
     * 证据块唯一标识。
     */
    String blockId;
    /**
     * 证据来源类型，例如知识库、工具或记忆。
     */
    String sourceType;
    /**
     * 证据标题。
     */
    String title;
    /**
     * 证据正文内容。
     */
    String content;
    /**
     * 证据相关性得分。
     */
    Double score;
    /**
     * 证据关联的扩展元数据。
     */
    Map<String, Object> metadata;
}
