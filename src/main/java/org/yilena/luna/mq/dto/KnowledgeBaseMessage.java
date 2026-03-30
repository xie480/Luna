package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * KnowledgeBaseMessage ??
 */
public class KnowledgeBaseMessage implements Serializable {
    private String title;
    private String content;
    private String sourceType; // 存儲枚舉的 name()
    private String sourcePath;
}
