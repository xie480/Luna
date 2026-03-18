package org.yilena.luna.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON Schema 校驗器
 * 用於在 LLM 生成參數後，執行前進行強制校驗
 */
@Slf4j
public class JsonSchemaValidator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 校驗 JSON 字符串是否符合指定的 JSON Schema
     * 目前實現為基礎的 JSON 格式校驗，生產環境建議引入 networknt/json-schema-validator
     *
     * @param schema JSON Schema 字符串
     * @param json   LLM 生成的參數 JSON 字符串
     * @return 是否校驗通過
     */
    public static boolean validate(String schema, String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            // 1. 基礎格式校驗：確保是合法的 JSON
            objectMapper.readTree(json);

            // 2. TODO: 引入完整的 Schema 校驗庫進行字段類型、必填項檢查
            // 目前假設只要是合法的 JSON 且 Schema 存在即視為通過
            return schema != null && !schema.isBlank();
        } catch (Exception e) {
            log.warn("JSON 校驗失敗: {}", e.getMessage());
            return false;
        }
    }
}
