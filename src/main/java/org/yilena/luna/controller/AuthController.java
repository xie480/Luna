package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.constants.HttpConstants;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.MessageConstants;
import org.yilena.luna.entity.LoginRequest;
import org.yilena.luna.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "认证接口", description = "提供登录和退出登录能力")
/**
 * 认证控制器，负责暴露登录和退出登录接口。
 */
public class AuthController {

    @Autowired
    /**
     * 认证服务，负责账号校验和令牌管理。
     */
    private AuthService authService;

    @PostMapping("/login")
    /**
     * 校验用户名和密码并签发新的认证令牌。
     */
    @Operation(summary = "用户登录", description = "校验登录凭证并返回当前服务实例生成的认证令牌")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        String token = authService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(Map.of(JsonFieldConstants.TOKEN, token));
    }

    @PostMapping("/logout")
    /**
     * 根据请求头中的令牌执行退出登录，失效当前会话凭证。
     */
    @Operation(summary = "用户退出登录", description = "根据请求头中的认证令牌执行登出并清理服务端登录状态")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = HttpConstants.HEADER_AUTHORIZATION, required = false) String token) {
        authService.logout(token);
        return ResponseEntity.ok(Map.of(JsonFieldConstants.MESSAGE, MessageConstants.LOGOUT_SUCCESS));
    }
}
