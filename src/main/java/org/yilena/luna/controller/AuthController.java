package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.LoginRequest;
import org.yilena.luna.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "鉴权模块")
/**
 * AuthController ??
 */
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "登录接口，返回 Token，服务重启后失效")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        // 验证用户名密码并签发 JWT。
        String token = authService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出接口，清除 Token")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String token) {
        // 注销当前令牌并加入服务内黑名单。
        authService.logout(token);
        return ResponseEntity.ok(Map.of("message", "已登出"));
    }
}
