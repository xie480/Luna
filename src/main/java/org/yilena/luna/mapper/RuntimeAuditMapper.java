package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RuntimeAuditMapper {

    @Insert("""
            insert into plan_context_snapshot(plan_id, node_id, session_id, context_package_json, created_at)
            select current_plan_id, null, #{sessionId}, cast(#{payload} as jsonb), current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertContextSnapshot(@Param("sessionId") String sessionId, @Param("payload") String payload);

    @Insert("""
            insert into plan_decision_record(plan_id, node_id, decision_type, decision_reason, decision_payload, created_at)
            select current_plan_id, null, #{decisionType}, #{decisionReason}, cast(#{decisionPayload} as jsonb), current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertDecisionRecord(@Param("sessionId") String sessionId,
                             @Param("decisionType") String decisionType,
                             @Param("decisionReason") String decisionReason,
                             @Param("decisionPayload") String decisionPayload);

    @Insert("""
            insert into tool_execution_trace(plan_id, node_id, session_id, tool_name, call_status, normalized_input, normalized_output, error_message, latency_ms, created_at)
            select current_plan_id, null, #{sessionId}, #{toolName}, #{callStatus},
                   cast(#{normalizedInput} as jsonb), cast(#{normalizedOutput} as jsonb),
                   #{errorMessage}, #{latencyMs}, current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertToolExecutionTrace(@Param("sessionId") String sessionId,
                                 @Param("toolName") String toolName,
                                 @Param("callStatus") String callStatus,
                                 @Param("normalizedInput") String normalizedInput,
                                 @Param("normalizedOutput") String normalizedOutput,
                                 @Param("errorMessage") String errorMessage,
                                 @Param("latencyMs") Long latencyMs);
}
