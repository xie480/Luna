package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
/**
 * 离线学习 Mapper，负责执行记忆归档、批量整理等离线学习前置数据处理操作，
 * 为后续离线分析与模型增强提供底层支持。
 */
public interface OfflineLearningMapper {

    @Update("""
            update memory_registry
            set archived = true
            where archived = false and confidence_score < 0.30 and importance_score < 0.30
              and created_at < current_timestamp - interval '30 day'
            """)
    int archiveLowQualityMemory();

    @Update("""
            update task_semantic_fact t
            set deleted = true, updated_at = current_timestamp
            where t.deleted = false
              and exists (
                select 1 from task_semantic_fact newer
                where newer.deleted = false
                  and coalesce(newer.principal_id, -1) = coalesce(t.principal_id, -1)
                  and newer.fact_key = t.fact_key
                  and coalesce(newer.fact_value_text, '') = coalesce(t.fact_value_text, '')
                  and newer.fact_id > t.fact_id
              )
            """)
    int mergeDuplicateTaskFacts();

    @Update("""
            update relational_semantic_fact t
            set deleted = true, updated_at = current_timestamp
            where t.deleted = false
              and exists (
                select 1 from relational_semantic_fact newer
                where newer.deleted = false
                  and coalesce(newer.principal_id, -1) = coalesce(t.principal_id, -1)
                  and newer.fact_key = t.fact_key
                  and coalesce(newer.fact_value_text, '') = coalesce(t.fact_value_text, '')
                  and newer.fact_id > t.fact_id
              )
            """)
    int mergeDuplicateRelationalFacts();

    @Update("""
            update task_procedure_pattern
            set confidence_score = case when usage_count <= 0 then confidence_score
                                   else least(greatest((success_count::numeric + 1) / (usage_count::numeric + 2), 0.05), 0.98)
                                   end,
                updated_at = current_timestamp
            """)
    int rankTaskProcedures();

    @Update("""
            update relational_procedure_pattern
            set confidence_score = case when usage_count <= 0 then confidence_score
                                   else least(greatest((success_count::numeric + 1) / (usage_count::numeric + 2), 0.05), 0.98)
                                   end,
                updated_at = current_timestamp
            """)
    int rankRelationalProcedures();

    @Update("""
            with stats as (
                select count(*) filter (where episode_type = 'SUCCESS') as success_cnt,
                       count(*) filter (where episode_type = 'FAILURE') as fail_cnt,
                       count(*) as usage_cnt
                from task_episode
                where created_at >= current_timestamp - interval '30 day'
            )
            update task_procedure_pattern p
            set usage_count = greatest(p.usage_count, coalesce(stats.usage_cnt, 0)),
                success_count = greatest(p.success_count, coalesce(stats.success_cnt, 0)),
                fail_count = greatest(p.fail_count, coalesce(stats.fail_cnt, 0)),
                updated_at = current_timestamp
            from stats
            where p.name = 'default_task_execution'
            """)
    int syncTaskProcedureStatsFromEpisodes();

    @Update("""
            with stats as (
                select count(*) filter (where coalesce(response_effectiveness, 0.5) >= 0.65) as success_cnt,
                       count(*) filter (where coalesce(response_effectiveness, 0.5) < 0.65) as fail_cnt,
                       count(*) as usage_cnt
                from relational_episode
                where created_at >= current_timestamp - interval '30 day'
            )
            update relational_procedure_pattern p
            set usage_count = greatest(p.usage_count, coalesce(stats.usage_cnt, 0)),
                success_count = greatest(p.success_count, coalesce(stats.success_cnt, 0)),
                fail_count = greatest(p.fail_count, coalesce(stats.fail_cnt, 0)),
                updated_at = current_timestamp
            from stats
            where p.name = 'default_relational_support'
            """)
    int syncRelationalProcedureStatsFromEpisodes();

    @Update("""
            update relational_profile rp
            set trust_score = coalesce(stats.avg_effectiveness, rp.trust_score),
                intimacy_score = coalesce(stats.avg_quality, rp.intimacy_score),
                updated_at = current_timestamp
            from (
                select principal_id,
                       least(greatest(avg(coalesce(response_effectiveness, 0.5)), 0.0), 1.0) as avg_effectiveness,
                       least(greatest(avg(coalesce(interaction_quality, 0.5)), 0.0), 1.0) as avg_quality
                from relational_episode
                group by principal_id
            ) stats
            where rp.principal_id = stats.principal_id
            """)
    int calibrateRelationalProfileScores();

    @Update("""
            update task_procedure_pattern p
            set usage_count = p.usage_count + stats.reflection_cnt,
                fail_count = p.fail_count + stats.reflection_cnt,
                confidence_score = least(greatest(p.confidence_score * 0.90, 0.05), 0.98),
                updated_at = current_timestamp
            from (
                select count(*) as reflection_cnt
                from task_reflection_record
                where created_at >= current_timestamp - interval '7 day'
            ) stats
            where p.name = 'default_failure_recovery'
              and stats.reflection_cnt > 0
            """)
    int mineTaskRecoveryFromReflections();

    @Update("""
            update relational_procedure_pattern p
            set usage_count = p.usage_count + stats.reflection_cnt,
                fail_count = p.fail_count + stats.reflection_cnt,
                confidence_score = least(greatest(p.confidence_score * 0.92, 0.05), 0.98),
                updated_at = current_timestamp
            from (
                select count(*) as reflection_cnt
                from relational_reflection_record
                where created_at >= current_timestamp - interval '7 day'
            ) stats
            where p.name = 'default_relation_repair'
              and stats.reflection_cnt > 0
            """)
    int mineRelationRepairFromReflections();

    @Update("""
            insert into memory_relation(from_memory_id, to_memory_id, relation_type, weight, created_at)
            select newer_mem.memory_id, older_mem.memory_id, 'CONTRADICTS', 0.85, current_timestamp
            from task_semantic_fact newer
            join task_semantic_fact older
              on coalesce(newer.principal_id, -1) = coalesce(older.principal_id, -1)
             and newer.fact_key = older.fact_key
             and newer.fact_id > older.fact_id
             and coalesce(newer.fact_value_text,'') <> coalesce(older.fact_value_text,'')
             and newer.deleted = false
             and older.deleted = false
            join memory_registry newer_mem
              on newer_mem.ref_table = 'task_semantic_fact'
             and newer_mem.ref_id = cast(newer.fact_id as varchar)
            join memory_registry older_mem
              on older_mem.ref_table = 'task_semantic_fact'
             and older_mem.ref_id = cast(older.fact_id as varchar)
            where not exists (
                select 1 from memory_relation r
                where r.from_memory_id = newer_mem.memory_id
                  and r.to_memory_id = older_mem.memory_id
                  and r.relation_type = 'CONTRADICTS'
            )
            """)
    int linkTaskFactContradictions();

    @Update("""
            insert into memory_relation(from_memory_id, to_memory_id, relation_type, weight, created_at)
            select newer_mem.memory_id, older_mem.memory_id, 'CONTRADICTS', 0.85, current_timestamp
            from relational_semantic_fact newer
            join relational_semantic_fact older
              on coalesce(newer.principal_id, -1) = coalesce(older.principal_id, -1)
             and newer.fact_key = older.fact_key
             and newer.fact_id > older.fact_id
             and coalesce(newer.fact_value_text,'') <> coalesce(older.fact_value_text,'')
             and newer.deleted = false
             and older.deleted = false
            join memory_registry newer_mem
              on newer_mem.ref_table = 'relational_semantic_fact'
             and newer_mem.ref_id = cast(newer.fact_id as varchar)
            join memory_registry older_mem
              on older_mem.ref_table = 'relational_semantic_fact'
             and older_mem.ref_id = cast(older.fact_id as varchar)
            where not exists (
                select 1 from memory_relation r
                where r.from_memory_id = newer_mem.memory_id
                  and r.to_memory_id = older_mem.memory_id
                  and r.relation_type = 'CONTRADICTS'
            )
            """)
    int linkRelationalFactContradictions();

    @Update("""
            insert into memory_relation(from_memory_id, to_memory_id, relation_type, weight, created_at)
            select ep.memory_id, proc.memory_id, 'GENERALIZES', 0.68, current_timestamp
            from memory_registry ep
            join memory_registry proc on proc.principal_id = ep.principal_id
            where ep.ref_table in ('task_episode','relational_episode')
              and proc.ref_table in ('task_procedure_pattern','relational_procedure_pattern')
              and not exists (
                select 1 from memory_relation r
                where r.from_memory_id = ep.memory_id
                  and r.to_memory_id = proc.memory_id
                  and r.relation_type = 'GENERALIZES'
              )
            """)
    int linkEpisodeGeneralizations();
}
