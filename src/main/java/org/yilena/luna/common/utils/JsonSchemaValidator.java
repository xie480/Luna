package org.yilena.luna.common.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * JSON Schema 校驗器
 * 用於在 LLM 生成參數後，執行前進行強制校驗
 */
@Slf4j
public class JsonSchemaValidator {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /**
     * 校驗 JSON 字符串是否符合指定的 JSON Schema
     *
     * @param schemaJson JSON Schema 字符串
     * @param json       LLM 生成的參數 JSON 字符串
     * @return 是否校驗通過
     */
    public static boolean validate(String schemaJson, String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        if (schemaJson == null || schemaJson.isBlank()) {
            // 如果沒有定義 Schema，默認通過（或者根據業務需求拒絕）
            return true;
        }

        try {
            JsonNode jsonNode = objectMapper.readTree(json);
            JsonSchema schema = schemaFactory.getSchema(schemaJson);
            Set<ValidationMessage> errors = schema.validate(jsonNode);

            if (!errors.isEmpty()) {
                log.warn("JSON Schema 校驗失敗。錯誤信息：");
                for (ValidationMessage error : errors) {
                    log.warn("- {}", error.getMessage());
                }
                return false;
            }
            return true;

        } catch (Exception e) {
            log.error("JSON 校驗過程中發生異常: {}", e.getMessage());
            return false;
        }
    }
}
