package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * BaseTool ??
 */
public abstract class BaseTool {

    protected final ObjectMapper objectMapper; // 声明成员字段

    public BaseTool(ObjectMapper objectMapper) { // 定义方法签名
        this.objectMapper = objectMapper; // 执行赋值操作
    } // 结束当前代码块

    protected String success(Object data) { // 定义方法签名
        try { // 尝试执行核心逻辑
            Map<String, Object> map = new HashMap<>(); // 执行赋值操作
            map.put("status", "success"); // 执行语句逻辑
            map.put("data", data); // 执行语句逻辑
            return objectMapper.writeValueAsString(map); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return "{\"status\":\"error\", \"message\":\"JSON序列化失败\"}"; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    protected String error(String message) { // 定义方法签名
        try { // 尝试执行核心逻辑
            Map<String, Object> map = new HashMap<>(); // 执行赋值操作
            map.put("status", "error"); // 执行语句逻辑
            map.put("message", message); // 执行语句逻辑
            return objectMapper.writeValueAsString(map); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return "{\"status\":\"error\", \"message\":\"" + message + "\"}"; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
