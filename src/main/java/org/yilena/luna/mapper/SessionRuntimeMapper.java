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
public interface SessionRuntimeMapper {

    @Select("select task_state from agent_session where session_id = #{sessionId} limit 1")
    String selectTaskState(@Param("sessionId") String sessionId);

    @Select("select relational_state from agent_session where session_id = #{sessionId} limit 1")
    String selectRelationalState(@Param("sessionId") String sessionId);

    @Select("select cast(abs(hashtext(#{sessionId})) as bigint)")
    Long selectPrincipalId(@Param("sessionId") String sessionId);

    @Update("""
            insert into principal(principal_id, principal_type, display_name, profile_json, created_at, updated_at)
            values (#{principalId}, 'USER', #{displayName}, jsonb_build_object('source','session_orchestrator'), current_timestamp, current_timestamp)
            on conflict (principal_id)
            do update set display_name = excluded.display_name, updated_at = current_timestamp
            """)
    int upsertPrincipal(@Param("principalId") Long principalId, @Param("displayName") String displayName);

    @Update("""
            insert into agent_session(session_id, principal_id, session_type, task_state, relational_state, current_goal,
                                      last_user_message_at, metadata_json, created_at, updated_at)
            values (#{sessionId}, #{principalId}, 'HYBRID', #{taskState}, #{relationalState}, #{goal},
                    current_timestamp, jsonb_build_object('source','session_orchestrator'), current_timestamp, current_timestamp)
            on conflict (session_id)
            do update set principal_id = excluded.principal_id,
                          task_state = excluded.task_state,
                          relational_state = excluded.relational_state,
                          current_goal = excluded.current_goal,
                          last_user_message_at = current_timestamp,
                          updated_at = current_timestamp
            """)
    int upsertSession(@Param("sessionId") String sessionId,
                      @Param("principalId") Long principalId,
                      @Param("taskState") String taskState,
                      @Param("relationalState") String relationalState,
                      @Param("goal") String goal);

    @Insert("""
            insert into state_transition_log(session_id, state_domain, from_state, to_state, trigger_type, trigger_ref, reason, payload_json, created_at)
            values (#{sessionId}, #{domain}, #{fromState}, #{toState}, 'USER_INPUT', #{triggerRef}, 'state_update', jsonb_build_object('text', #{text}), current_timestamp)
            """)
    int insertTransition(@Param("sessionId") String sessionId,
                         @Param("domain") String domain,
                         @Param("fromState") String fromState,
                         @Param("toState") String toState,
                         @Param("triggerRef") String triggerRef,
                         @Param("text") String text);

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
