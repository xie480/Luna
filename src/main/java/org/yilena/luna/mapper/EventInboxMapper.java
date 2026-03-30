package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface EventInboxMapper {

    @Select("""
            insert into event_inbox(session_id, event_type, payload_json, status, trace_id, created_at, updated_at)
            values (#{sessionId}, #{eventType}, jsonb_build_object('text', #{text}), 'PENDING', #{traceId}, current_timestamp, current_timestamp)
            returning event_id
            """)
    Long insertPendingEvent(@Param("sessionId") String sessionId,
                            @Param("eventType") String eventType,
                            @Param("text") String text,
                            @Param("traceId") String traceId);

    @Select("""
            select event_id, session_id, event_type, payload_json, trace_id
            from event_inbox
            where status = 'PENDING'
            order by created_at asc
            limit #{limit}
            """)
    List<Map<String, Object>> selectPendingEvents(@Param("limit") int limit);

    @Update("""
            update event_inbox
            set status = 'PROCESSED', updated_at = current_timestamp
            where event_id = #{eventId}
            """)
    int markProcessed(@Param("eventId") Long eventId);

    @Update("""
            update event_inbox
            set status = 'FAILED', updated_at = current_timestamp
            where event_id = #{eventId}
            """)
    int markFailed(@Param("eventId") Long eventId);
}
