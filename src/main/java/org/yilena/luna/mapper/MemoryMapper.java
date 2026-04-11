package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.Memory;

import java.util.List;
import java.util.Map;

@Mapper
/**
 * 记忆查询 Mapper，负责聚合短期记忆、长期记忆和语义事实等多类记忆数据的检索查询，
 * 为记忆管理和运行时上下文召回提供底层访问能力。
 */
public interface MemoryMapper extends BaseMapper<Memory> {

    @Select("""
            select id, session_id, memory_type, content, weight, created_at, updated_at
            from luna_memory
            where session_id = #{sessionId}
            order by updated_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> selectResourceMemoryBySessionId(@Param("sessionId") String sessionId,
                                                              @Param("limit") int limit);

    @Select("""
            select * from (
                select fact_id as id,
                       null::varchar as session_id,
                       'FACT' as memory_type,
                       fact_value_text as content,
                       cast(coalesce(confidence_score, 0.5) * 100 as int) as weight,
                       embedding,
                       created_at,
                       updated_at
                from task_semantic_fact
                where deleted = false and embedding is not null
                union all
                select fact_id as id,
                       null::varchar as session_id,
                       'PREFERENCE' as memory_type,
                       fact_value_text as content,
                       cast(coalesce(confidence_score, 0.5) * 100 as int) as weight,
                       embedding,
                       created_at,
                       updated_at
                from relational_semantic_fact
                where deleted = false and embedding is not null
            ) t
            order by t.embedding::vector <-> #{vector}::vector
            limit #{topK}
            """)
    List<Memory> searchByVector(@Param("vector") String vector, @Param("topK") int topK);

    @Select("""
            select * from (
                select f.fact_id as id,
                       #{sessionId} as session_id,
                       'FACT' as memory_type,
                       f.fact_value_text as content,
                       cast(coalesce(f.confidence_score, 0.5) * 100 as int) as weight,
                       f.embedding,
                       f.created_at,
                       f.updated_at
                from task_semantic_fact f
                where f.deleted = false
                  and f.embedding is not null
                  and f.principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1)
                union all
                select r.fact_id as id,
                       #{sessionId} as session_id,
                       'PREFERENCE' as memory_type,
                       r.fact_value_text as content,
                       cast(coalesce(r.confidence_score, 0.5) * 100 as int) as weight,
                       r.embedding,
                       r.created_at,
                       r.updated_at
                from relational_semantic_fact r
                where r.deleted = false
                  and r.embedding is not null
                  and r.principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1)
            ) t
            order by t.embedding::vector <-> #{vector}::vector
            limit #{topK}
            """)
    List<Memory> searchByVectorAndSessionId(@Param("vector") String vector, @Param("sessionId") String sessionId, @Param("topK") int topK);
}
