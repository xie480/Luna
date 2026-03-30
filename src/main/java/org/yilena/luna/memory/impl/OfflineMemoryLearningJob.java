package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineMemoryLearningJob {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "${luna.memory.learning.cron:0 20 3 * * *}")
    public void runDailyLearning() {
        int archived = archiveLowQualityMemory();
        int mergedTaskFacts = mergeDuplicateTaskFacts();
        int mergedRelationFacts = mergeDuplicateRelationalFacts();
        int taskProcRanked = rankTaskProcedures();
        int relationProcRanked = rankRelationalProcedures();
        int profileUpdated = calibrateRelationalProfileScores();

        log.info("offline learning done: archived={}, mergedTaskFacts={}, mergedRelationFacts={}, taskProcRanked={}, relationProcRanked={}, profileUpdated={}",
                archived, mergedTaskFacts, mergedRelationFacts, taskProcRanked, relationProcRanked, profileUpdated);
    }

    private int archiveLowQualityMemory() {
        try {
            return jdbcTemplate.update(
                    "update memory_registry set archived = true " +
                            "where archived = false and confidence_score < 0.30 and importance_score < 0.30 " +
                            "and created_at < current_timestamp - interval '30 day'"
            );
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int mergeDuplicateTaskFacts() {
        try {
            return jdbcTemplate.update(
                    "update task_semantic_fact t set deleted = true, updated_at = current_timestamp " +
                            "where t.deleted = false and exists (" +
                            "select 1 from task_semantic_fact newer " +
                            "where newer.deleted = false " +
                            "and coalesce(newer.principal_id, -1) = coalesce(t.principal_id, -1) " +
                            "and newer.fact_key = t.fact_key " +
                            "and coalesce(newer.fact_value_text, '') = coalesce(t.fact_value_text, '') " +
                            "and newer.fact_id > t.fact_id)"
            );
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int mergeDuplicateRelationalFacts() {
        try {
            return jdbcTemplate.update(
                    "update relational_semantic_fact t set deleted = true, updated_at = current_timestamp " +
                            "where t.deleted = false and exists (" +
                            "select 1 from relational_semantic_fact newer " +
                            "where newer.deleted = false " +
                            "and coalesce(newer.principal_id, -1) = coalesce(t.principal_id, -1) " +
                            "and newer.fact_key = t.fact_key " +
                            "and coalesce(newer.fact_value_text, '') = coalesce(t.fact_value_text, '') " +
                            "and newer.fact_id > t.fact_id)"
            );
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int rankTaskProcedures() {
        try {
            return jdbcTemplate.update(
                    "update task_procedure_pattern set " +
                            "confidence_score = case when usage_count <= 0 then confidence_score else " +
                            "least(greatest((success_count::numeric + 1) / (usage_count::numeric + 2), 0.05), 0.98) end, " +
                            "updated_at = current_timestamp"
            );
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int rankRelationalProcedures() {
        try {
            return jdbcTemplate.update(
                    "update relational_procedure_pattern set " +
                            "confidence_score = case when usage_count <= 0 then confidence_score else " +
                            "least(greatest((success_count::numeric + 1) / (usage_count::numeric + 2), 0.05), 0.98) end, " +
                            "updated_at = current_timestamp"
            );
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int calibrateRelationalProfileScores() {
        try {
            return jdbcTemplate.update(
                    "update relational_profile rp set " +
                            "trust_score = coalesce(stats.avg_effectiveness, rp.trust_score), " +
                            "intimacy_score = coalesce(stats.avg_quality, rp.intimacy_score), " +
                            "updated_at = current_timestamp " +
                            "from (" +
                            "select principal_id, " +
                            "least(greatest(avg(coalesce(response_effectiveness, 0.5)), 0.0), 1.0) as avg_effectiveness, " +
                            "least(greatest(avg(coalesce(interaction_quality, 0.5)), 0.0), 1.0) as avg_quality " +
                            "from relational_episode group by principal_id" +
                            ") stats " +
                            "where rp.principal_id = stats.principal_id"
            );
        } catch (Exception ignore) {
            return 0;
        }
    }
}
