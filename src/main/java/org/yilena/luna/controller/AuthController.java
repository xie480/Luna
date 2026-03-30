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

    @Autowired // 声明注解
    private AuthService authService; // 声明成员字段

    @PostMapping("/login") // 声明注解
    @Operation(summary = "登录接口，返回 Token，服务重启后失效") // 声明注解
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) { // 定义方法签名
        String token = authService.login(request.getUsername(), request.getPassword()); // 执行赋值操作
        return ResponseEntity.ok(Map.of("token", token)); // 返回处理结果
    } // 结束当前代码块

    @PostMapping("/logout") // 声明注解
    @Operation(summary = "登出接口，清除 Token") // 声明注解
    public ResponseEntity<Map<String, String>> logout( // 定义方法签名
            @RequestHeader(value = "Authorization", required = false) String token) { // 声明注解
        authService.logout(token); // 执行语句逻辑
        return ResponseEntity.ok(Map.of("message", "已登出")); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
