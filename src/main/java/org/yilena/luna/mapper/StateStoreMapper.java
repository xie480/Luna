package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

@Mapper
public interface StateStoreMapper {

    @Insert("""
            insert into task_working_memory(session_id, principal_id, plan_id, version, updated_at)
            select s.session_id, s.principal_id, s.current_plan_id, 1, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            on conflict (session_id)
            do update set updated_at = current_timestamp
            """)
    int ensureTaskWorkingMemory(@Param("sessionId") String sessionId);

    @Update("""
            insert into task_working_memory_slot(twm_id, slot_name, slot_type, slot_value_json, priority, freshness_score, source_type, source_ref, updated_at)
            select twm.twm_id, #{slotName}, 'JSON', cast(#{slotValueJson} as jsonb), #{priority}, 1.0, #{sourceType}, #{sourceRef}, current_timestamp
            from task_working_memory twm
            where twm.session_id = #{sessionId}
            on conflict (twm_id, slot_name)
            do update set
                slot_type = excluded.slot_type,
                slot_value_json = excluded.slot_value_json,
                priority = excluded.priority,
                freshness_score = excluded.freshness_score,
                source_type = excluded.source_type,
                source_ref = excluded.source_ref,
                updated_at = current_timestamp
            """)
    int upsertStateSlot(@Param("sessionId") String sessionId,
                        @Param("slotName") String slotName,
                        @Param("slotValueJson") String slotValueJson,
                        @Param("priority") int priority,
                        @Param("sourceType") String sourceType,
                        @Param("sourceRef") String sourceRef);

    @Select("""
            select slot_value_json::text as slot_value_json, updated_at
            from task_working_memory_slot
            where twm_id = (
                select twm_id from task_working_memory where session_id = #{sessionId} limit 1
            )
              and slot_name = #{slotName}
            limit 1
            """)
    Map<String, Object> selectStateSlot(@Param("sessionId") String sessionId, @Param("slotName") String slotName);
}

