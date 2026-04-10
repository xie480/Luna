package org.yilena.luna.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录请求参数")
/**
 * 登录请求对象，负责承接前端提交的账号和密码。
 */
public class LoginRequest {

    @Schema(description = "登录用户名")
    /**
     * 登录账号名称。
     */
    private String username;

    @Schema(description = "登录密码")
    /**
     * 登录账号密码。
     */
    private String password;
}
