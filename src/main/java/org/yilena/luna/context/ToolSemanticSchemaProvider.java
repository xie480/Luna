package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.enums.ToolStatusEnum;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 该组件负责提供工具语义结果的 JSON Schema，供语义结果校验阶段统一使用。
 */
@Component
public class ToolSemanticSchemaProvider {

    /**
     * 根据枚举状态动态拼接 schema 中允许的工具状态列表。
     */
    private static final String TOOL_STATUS_ENUM =
            Arrays.stream(ToolStatusEnum.codes())
                    .map(code -> "\"" + code + "\"")
                    .collect(Collectors.joining(","));

    /**
     * 工具语义结果的 JSON Schema 定义。
     */
    private static final String TOOL_SEMANTIC_SCHEMA = """
            {
              "$schema":"http://json-schema.org/draft-07/schema#",
              "type":"object",
              "additionalProperties":true,
              "required":[
                "toolStatus",
                "keyFacts",
                "businessImpact",
                "unresolvedIssues",
                "nextStepHint",
                "confidence"
              ],
              "properties":{
                "toolStatus":{
                  "type":"string",
                  "enum":[%s]
                },
                "keyFacts":{
                  "type":"array",
                  "items":{"type":"string"},
                  "minItems":1
                },
                "businessImpact":{"type":"string","minLength":1},
                "unresolvedIssues":{
                  "type":"array",
                  "items":{"type":"string"}
                },
                "nextStepHint":{"type":"string","minLength":1},
                "confidence":{"type":"number","minimum":0.0,"maximum":1.0}
              }
            }
            """.formatted(TOOL_STATUS_ENUM);

    /**
     * 返回工具语义结果的标准 schema 文本。
     */
    public String toolSemanticSchema() {
        return TOOL_SEMANTIC_SCHEMA;
    }
}
