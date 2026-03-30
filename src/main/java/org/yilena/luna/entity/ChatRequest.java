package org.yilena.luna.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "聊天请求参数")
/**
 * ChatRequest ??
 */
public class ChatRequest {

    @Schema(description = "用户输入内容") // 声明注解
    private String userInput; // 声明成员字段
} // 结束当前代码块
