package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.enums.ToolStatusEnum;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class ToolSemanticSchemaProvider {

    private static final String TOOL_STATUS_ENUM =
            Arrays.stream(ToolStatusEnum.codes())
                    .map(code -> "\"" + code + "\"")
                    .collect(Collectors.joining(","));

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

    public String toolSemanticSchema() {
        return TOOL_SEMANTIC_SCHEMA;
    }
}
