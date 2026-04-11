package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.ResultStatusConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具类基类，负责封装统一的成功/失败响应结构，供各类工具方法复用返回结果。
 */
public abstract class BaseTool {

    /**
     * JSON 序列化器，用于将工具执行结果转换为统一字符串响应。
     */
    protected final ObjectMapper objectMapper;

    public BaseTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构建工具调用成功响应，统一包装状态字段和业务数据。
     */
    protected String success(Object data) {
        try {
            /**
             * 先组装统一响应体，确保所有工具成功返回的结构保持一致。
             */
            Map<String, Object> map = new HashMap<>();
            map.put(JsonFieldConstants.STATUS, ResultStatusConstants.SUCCESS);
            map.put(JsonFieldConstants.DATA, data);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            /**
             * 序列化失败时返回兜底 JSON，避免工具层异常继续向外扩散。
             */
            return "{\"" + JsonFieldConstants.STATUS + "\":\"" + ResultStatusConstants.ERROR
                    + "\", \"" + JsonFieldConstants.MESSAGE + "\":\"JSON serialization failed\"}";
        }
    }

    /**
     * 构建工具调用失败响应，统一包装错误状态和失败原因。
     */
    protected String error(String message) {
        try {
            /**
             * 先组装失败响应体，便于上层按统一字段读取错误信息。
             */
            Map<String, Object> map = new HashMap<>();
            map.put(JsonFieldConstants.STATUS, ResultStatusConstants.ERROR);
            map.put(JsonFieldConstants.MESSAGE, message);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            /**
             * 序列化失败时直接拼接最小可用响应，保证错误信息仍可返回。
             */
            return "{\"" + JsonFieldConstants.STATUS + "\":\"" + ResultStatusConstants.ERROR
                    + "\", \"" + JsonFieldConstants.MESSAGE + "\":\"" + message + "\"}";
        }
    }
}
