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
 * JSON Schema 校验器，负责在工具调用前验证参数 JSON 是否满足声明的输入结构。
 */
@Slf4j
public class JsonSchemaValidator {

    /**
     * JSON 解析器。
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * JSON Schema 工厂，使用 Draft V7 规范执行校验。
     */
    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /**
     * 校验参数 JSON 是否满足指定 Schema。
     */
    public static boolean validate(String schemaJson, String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        if (schemaJson == null || schemaJson.isBlank()) {
            /**
             * 未声明 Schema 时默认放行，兼容不带结构约束的工具定义。
             */
            return true;
        }

        try {
            /**
             * 先解析待校验 JSON，再加载 Schema 并收集全部结构错误。
             */
            JsonNode jsonNode = objectMapper.readTree(json);
            JsonSchema schema = schemaFactory.getSchema(schemaJson);
            Set<ValidationMessage> errors = schema.validate(jsonNode);

            /**
             * 存在校验错误时逐条记录日志，便于定位参数结构不匹配原因。
             */
            if (!errors.isEmpty()) {
                log.warn("JSON Schema 校验失败，错误明细如下：");
                for (ValidationMessage error : errors) {
                    log.warn("- {}", error.getMessage());
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            /**
             * 解析或校验过程异常时统一判定为失败，避免非法参数继续向下执行。
             */
            log.error("JSON Schema 校验过程中发生异常: {}", e.getMessage());
            return false;
        }
    }
}
