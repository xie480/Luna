package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PerceptualBufferMapper {

    @Insert("""
            insert into task_perceptual_buffer(event_id, session_id, message_ref, tool_trace_ref, signal_json, created_at, ttl_at)
            values (#{eventId}, #{sessionId}, #{messageRef}, #{toolTraceRef}, cast(#{signalJson} as jsonb), current_timestamp,
                    current_timestamp + (#{ttlMinutes} || ' minutes')::interval)
            on conflict (event_id)
            do update set
                session_id = excluded.session_id,
                message_ref = excluded.message_ref,
                tool_trace_ref = excluded.tool_trace_ref,
                signal_json = excluded.signal_json,
                ttl_at = excluded.ttl_at
            """)
    int upsertTaskBuffer(@Param("eventId") String eventId,
                         @Param("sessionId") String sessionId,
                         @Param("messageRef") String messageRef,
                         @Param("toolTraceRef") String toolTraceRef,
                         @Param("signalJson") String signalJson,
                         @Param("ttlMinutes") int ttlMinutes);

    @Insert("""
            insert into relational_perceptual_buffer(event_id, session_id, message_ref, emotion_signal_json, boundary_signal_json, created_at, ttl_at)
            values (#{eventId}, #{sessionId}, #{messageRef}, cast(#{emotionSignalJson} as jsonb), cast(#{boundarySignalJson} as jsonb), current_timestamp,
                    current_timestamp + (#{ttlMinutes} || ' minutes')::interval)
            on conflict (event_id)
            do update set
                session_id = excluded.session_id,
                message_ref = excluded.message_ref,
                emotion_signal_json = excluded.emotion_signal_json,
                boundary_signal_json = excluded.boundary_signal_json,
                ttl_at = excluded.ttl_at
            """)
    int upsertRelationalBuffer(@Param("eventId") String eventId,
                               @Param("sessionId") String sessionId,
                               @Param("messageRef") String messageRef,
                               @Param("emotionSignalJson") String emotionSignalJson,
                               @Param("boundarySignalJson") String boundarySignalJson,
                               @Param("ttlMinutes") int ttlMinutes);

    @Delete("delete from task_perceptual_buffer where ttl_at <= current_timestamp")
    int deleteExpiredTaskBuffer();

    @Delete("delete from relational_perceptual_buffer where ttl_at <= current_timestamp")
    int deleteExpiredRelationalBuffer();
}

