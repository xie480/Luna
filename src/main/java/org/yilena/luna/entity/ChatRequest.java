package org.yilena.luna.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "对话请求参数")
/**
 * 对话请求对象，负责承接前端发送的一轮用户输入。
 */
public class ChatRequest {

    @Schema(description = "用户输入的原始文本内容")
    /**
     * 当前轮次用户提交的文本内容。
     */
    private String userInput;
}
