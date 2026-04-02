package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface RuntimeAuditMapper {

    @Insert("""
            insert into plan_context_snapshot(plan_id, node_id, session_id, context_package_json, created_at)
            select coalesce(
                       #{planId},
                       current_plan_id,
                       (select pcs.plan_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   coalesce(
                       #{nodeId},
                       (select active_node_id from task_working_memory where session_id = #{sessionId} order by updated_at desc limit 1),
                       (select pcs.node_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   #{sessionId},
                   cast(#{payload} as jsonb),
                   current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertContextSnapshot(@Param("sessionId") String sessionId,
                              @Param("planId") Long planId,
                              @Param("nodeId") Long nodeId,
                              @Param("payload") String payload);

    @Select("""
            insert into plan_context_snapshot(plan_id, node_id, session_id, context_package_json, created_at)
            select coalesce(
                       #{planId},
                       current_plan_id,
                       (select pcs.plan_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   coalesce(
                       #{nodeId},
                       (select active_node_id from task_working_memory where session_id = #{sessionId} order by updated_at desc limit 1),
                       (select pcs.node_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   #{sessionId},
                   cast(#{payload} as jsonb),
                   current_timestamp
            from agent_session
            where session_id = #{sessionId}
            returning id
            """)
    Long insertContextSnapshotAndReturnId(@Param("sessionId") String sessionId,
                                          @Param("planId") Long planId,
                                          @Param("nodeId") Long nodeId,
                                          @Param("payload") String payload);

    @Select("""
            select id, plan_id, node_id, session_id, context_package_json::text as context_package_json
            from plan_context_snapshot
            where session_id = #{sessionId}
              and id = #{snapshotId}
            limit 1
            """)
    Map<String, Object> selectContextSnapshotById(@Param("sessionId") String sessionId,
                                                  @Param("snapshotId") Long snapshotId);

    @Select("""
            select id, plan_id, node_id, session_id, context_package_json::text as context_package_json
            from plan_context_snapshot
            where session_id = #{sessionId}
            order by created_at desc, id desc
            limit 1
            """)
    Map<String, Object> selectLatestContextSnapshotBySession(@Param("sessionId") String sessionId);

    @Insert("""
            insert into plan_decision_record(plan_id, node_id, decision_type, decision_reason, decision_payload, created_at)
            select coalesce(
                       #{planId},
                       current_plan_id,
                       (select pcs.plan_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   coalesce(
                       #{nodeId},
                       (select active_node_id from task_working_memory where session_id = #{sessionId} order by updated_at desc limit 1),
                       (select pcs.node_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   #{decisionType},
                   #{decisionReason},
                   cast(#{decisionPayload} as jsonb),
                   current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertDecisionRecord(@Param("sessionId") String sessionId,
                             @Param("planId") Long planId,
                             @Param("nodeId") Long nodeId,
                             @Param("decisionType") String decisionType,
                             @Param("decisionReason") String decisionReason,
                             @Param("decisionPayload") String decisionPayload);

    @Insert("""
            insert into tool_execution_trace(plan_id, node_id, session_id, tool_name, call_status, normalized_input, normalized_output, error_message, latency_ms, created_at)
            select coalesce(
                       #{planId},
                       current_plan_id,
                       (select pcs.plan_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   coalesce(
                       #{nodeId},
                       (select active_node_id from task_working_memory where session_id = #{sessionId} order by updated_at desc limit 1),
                       (select pcs.node_id from plan_context_snapshot pcs where pcs.session_id = #{sessionId} order by pcs.created_at desc limit 1)
                   ),
                   #{sessionId}, #{toolName}, #{callStatus},
                   cast(#{normalizedInput} as jsonb), cast(#{normalizedOutput} as jsonb),
                   #{errorMessage}, #{latencyMs}, current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertToolExecutionTrace(@Param("sessionId") String sessionId,
                                 @Param("planId") Long planId,
                                 @Param("nodeId") Long nodeId,
                                 @Param("toolName") String toolName,
                                 @Param("callStatus") String callStatus,
                                 @Param("normalizedInput") String normalizedInput,
                                 @Param("normalizedOutput") String normalizedOutput,
                                 @Param("errorMessage") String errorMessage,
                                 @Param("latencyMs") Long latencyMs);
}
