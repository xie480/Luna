package org.yilena.luna.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录请求参数")
/**
 * LoginRequest ??
 */
public class LoginRequest {
    @Schema(description = "用户名") // 声明注解
    private String username; // 声明成员字段
    @Schema(description = "密码") // 声明注解
    private String password; // 声明成员字段
} // 结束当前代码块
