package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 知识库消息体，负责承载异步知识入库所需的标题、内容和来源信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseMessage implements Serializable {

    /**
     * 知识标题。
     */
    private String title;

    /**
     * 原始知识内容。
     */
    private String content;

    /**
     * 来源类型，通常存储枚举名称。
     */
    private String sourceType;

    /**
     * 来源路径或来源地址。
     */
    private String sourcePath;
}
