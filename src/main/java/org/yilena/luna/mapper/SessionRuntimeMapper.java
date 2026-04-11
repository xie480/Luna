package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
/**
 * 会话运行态 Mapper，负责读取和更新会话、计划、节点以及主体信息等运行态数据，
 * 为会话编排和状态切换提供底层访问能力。
 */
public interface SessionRuntimeMapper {

    @Select("select task_state from agent_session where session_id = #{sessionId} limit 1")
    String selectTaskState(@Param("sessionId") String sessionId);

    @Select("select relational_state from agent_session where session_id = #{sessionId} limit 1")
    String selectRelationalState(@Param("sessionId") String sessionId);

    @Select("select session_type from agent_session where session_id = #{sessionId} limit 1")
    String selectSessionType(@Param("sessionId") String sessionId);

    @Select("select principal_id from agent_session where session_id = #{sessionId} limit 1")
    Long selectPrincipalIdBySession(@Param("sessionId") String sessionId);

    @Select("select current_plan_id from agent_session where session_id = #{sessionId} limit 1")
    Long selectCurrentPlanIdBySession(@Param("sessionId") String sessionId);

    @Select("""
            select pi.plan_id, pi.status, pi.current_loop_index, pi.updated_at
            from plan_instance pi
            where pi.session_id = #{sessionId}
            order by case when pi.status in (1, 2) then 0 else 1 end, pi.updated_at desc
            limit 1
            """)
    Map<String, Object> selectLatestPlanRuntimeBySession(@Param("sessionId") String sessionId);

    @Select("""
            select pn.plan_id, pn.node_id, pn.status, pn.retry_count, pn.max_retry, pn.updated_at
            from plan_node pn
            where pn.plan_id = #{planId}
            order by case when pn.status in (1, 5) then 0 else 1 end, pn.updated_at desc
            limit 1
            """)
    Map<String, Object> selectLatestNodeRuntimeByPlanId(@Param("planId") String planId);

    @Select("""
            select node_type
            from plan_node
            where plan_id = #{planId}
              and node_id = #{nodeId}
            limit 1
            """)
    String selectNodeTypeByPlanAndNode(@Param("planId") Long planId, @Param("nodeId") Long nodeId);

    @Select("""
            select active_node_id
            from task_working_memory
            where session_id = #{sessionId}
            order by updated_at desc
            limit 1
            """)
    Long selectActiveNodeIdBySession(@Param("sessionId") String sessionId);

    @Select("""
            with existing as (
                select principal_id
                from principal
                where tenant_id = #{principalKey}
                order by updated_at desc
                limit 1
            ),
            inserted as (
                insert into principal(principal_type, tenant_id, display_name, profile_json, created_at, updated_at)
                select 'USER', #{principalKey}, #{displayName}, jsonb_build_object('source','auth_subject'), current_timestamp, current_timestamp
                where not exists (select 1 from existing)
                returning principal_id
            )
            select principal_id from inserted
            union all
            select principal_id from existing
            limit 1
            """)
    Long resolvePrincipalIdByKey(@Param("principalKey") String principalKey, @Param("displayName") String displayName);

    @Update("""
            update principal
            set display_name = #{displayName},
                updated_at = current_timestamp
            where principal_id = #{principalId}
            """)
    int touchPrincipal(@Param("principalId") Long principalId, @Param("displayName") String displayName);

    @Update("""
            insert into agent_session(session_id, principal_id, agent_id, session_type, task_state, relational_state, current_goal,
                                      last_user_message_at, metadata_json, created_at, updated_at)
            values (#{sessionId}, #{principalId}, #{agentId}, #{sessionType}, #{taskState}, #{relationalState}, #{goal},
                    current_timestamp, jsonb_build_object('source','session_orchestrator'), current_timestamp, current_timestamp)
            on conflict (session_id)
            do update set principal_id = excluded.principal_id,
                          agent_id = excluded.agent_id,
                          session_type = excluded.session_type,
                          task_state = excluded.task_state,
                          relational_state = excluded.relational_state,
                          current_goal = excluded.current_goal,
                          last_user_message_at = current_timestamp,
                          updated_at = current_timestamp
            """)
    int upsertSession(@Param("sessionId") String sessionId,
                      @Param("principalId") Long principalId,
                      @Param("agentId") Long agentId,
                      @Param("sessionType") String sessionType,
                      @Param("taskState") String taskState,
                      @Param("relationalState") String relationalState,
                      @Param("goal") String goal);

    @Update("""
            update agent_session
            set current_plan_id = #{planId},
                updated_at = current_timestamp
            where session_id = #{sessionId}
            """)
    int updateCurrentPlanId(@Param("sessionId") String sessionId, @Param("planId") Long planId);

    @Insert("""
            insert into state_transition_log(session_id, state_domain, from_state, to_state, trigger_type, trigger_ref, reason, payload_json, created_at)
            values (#{sessionId}, #{domain}, #{fromState}, #{toState}, #{triggerType}, #{triggerRef}, 'state_update', cast(#{payloadJson} as jsonb), current_timestamp)
            """)
    int insertTransition(@Param("sessionId") String sessionId,
                         @Param("domain") String domain,
                         @Param("fromState") String fromState,
                         @Param("toState") String toState,
                         @Param("triggerType") String triggerType,
                         @Param("triggerRef") String triggerRef,
                         @Param("payloadJson") String payloadJson);

    @Select("select agent_id from agent_identity order by agent_id asc limit 1")
    Long selectDefaultAgentId();

    @Update("""
            insert into agent_identity(agent_name, persona_name, persona_desc, default_tone, config_json, created_at, updated_at)
            select 'Luna', 'Luna', 'default runtime persona', 'clear_and_friendly',
                   jsonb_build_object('source','session_orchestrator'), current_timestamp, current_timestamp
            where not exists (select 1 from agent_identity)
            """)
    int ensureDefaultAgentIdentity();

    @Insert("""
            insert into conversation_message(session_id, role, message_type, content_text, created_at)
            values (#{sessionId}, #{role}, 'TEXT', #{content}, #{createdAt})
            """)
    int insertConversationMessage(@Param("sessionId") String sessionId,
                                  @Param("role") String role,
                                  @Param("content") String content,
                                  @Param("createdAt") LocalDateTime createdAt);

    @Select("""
            select role, content_text, created_at
            from conversation_message
            where session_id = #{sessionId}
            order by created_at asc
            limit 300
            """)
    List<Map<String, Object>> selectConversationMessages(@Param("sessionId") String sessionId);

    @Delete("""
            delete from conversation_message
            where session_id = #{sessionId}
              and role in ('USER','ASSISTANT')
            """)
    int deleteConversationUserAssistant(@Param("sessionId") String sessionId);

    @Select("""
            select distinct session_id
            from conversation_message
            where session_id like #{pattern}
            order by session_id asc
            """)
    List<Map<String, Object>> selectDistinctSessionIdsLike(@Param("pattern") String pattern);
}

