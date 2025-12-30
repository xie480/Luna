package org.yilena.runa.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "聊天请求参数")
public class ChatRequest {

    @Schema(description = "用户输入内容")
    private String userInput;
}
