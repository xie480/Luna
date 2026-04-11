package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
/**
 * 事件收件箱 Mapper，负责写入待处理事件并维护事件的处理状态流转。
 */
public interface EventInboxMapper {

    @Select("""
            insert into event_inbox(session_id, event_type, payload_json, status, trace_id, created_at, updated_at)
            values (#{sessionId}, #{eventType}, cast(#{payloadJson} as jsonb), 'PENDING', #{traceId}, current_timestamp, current_timestamp)
            returning event_id
            """)
    /**
     * 插入待处理事件并返回事件主键。
     */
    Long insertPendingEvent(@Param("sessionId") String sessionId,
                            @Param("eventType") String eventType,
                            @Param("payloadJson") String payloadJson,
                            @Param("traceId") String traceId);

    @Select("""
            select event_id, session_id, event_type, payload_json, trace_id
            from event_inbox
            where status = 'PENDING'
            order by created_at asc
            limit #{limit}
            """)
    /**
     * 查询待处理事件列表，按创建时间升序返回。
     */
    List<Map<String, Object>> selectPendingEvents(@Param("limit") int limit);

    @Update("""
            update event_inbox
            set status = 'PROCESSED', updated_at = current_timestamp
            where event_id = #{eventId}
            """)
    /**
     * 将指定事件标记为已处理。
     */
    int markProcessed(@Param("eventId") Long eventId);

    @Update("""
            update event_inbox
            set status = 'FAILED', updated_at = current_timestamp
            where event_id = #{eventId}
            """)
    /**
     * 将指定事件标记为处理失败。
     */
    int markFailed(@Param("eventId") Long eventId);
}
