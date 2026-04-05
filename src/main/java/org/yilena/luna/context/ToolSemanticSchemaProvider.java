package org.yilena.luna.context;

import org.springframework.stereotype.Component;

@Component
public class ToolSemanticSchemaProvider {

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
                  "enum":["SUCCESS","PENDING","FAILED","UNKNOWN"]
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
            """;

    public String toolSemanticSchema() {
        return TOOL_SEMANTIC_SCHEMA;
    }
}
