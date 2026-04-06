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
@Tag(name = "认证模块")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "登录接口，返回 Token（服务重启后失效）")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        String token = authService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(Map.of(JsonFieldConstants.TOKEN, token));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出接口，清除 Token")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = HttpConstants.HEADER_AUTHORIZATION, required = false) String token) {
        authService.logout(token);
        return ResponseEntity.ok(Map.of(JsonFieldConstants.MESSAGE, MessageConstants.LOGOUT_SUCCESS));
    }
}
