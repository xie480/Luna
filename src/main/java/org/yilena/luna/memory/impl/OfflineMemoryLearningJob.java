package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.OfflineLearningMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineMemoryLearningJob {

    private final OfflineLearningMapper offlineLearningMapper;

    @Scheduled(cron = "${luna.memory.learning.cron:0 20 3 * * *}")
    public void runDailyLearning() {
        int archived = archiveLowQualityMemory();
        int mergedTaskFacts = mergeDuplicateTaskFacts();
        int mergedRelationFacts = mergeDuplicateRelationalFacts();
        int taskStatsSynced = syncTaskProcedureStatsFromEpisodes();
        int relationStatsSynced = syncRelationalProcedureStatsFromEpisodes();
        int taskProcRanked = rankTaskProcedures();
        int relationProcRanked = rankRelationalProcedures();
        int profileUpdated = calibrateRelationalProfileScores();

        log.info("offline learning done: archived={}, mergedTaskFacts={}, mergedRelationFacts={}, taskStatsSynced={}, relationStatsSynced={}, taskProcRanked={}, relationProcRanked={}, profileUpdated={}",
                archived, mergedTaskFacts, mergedRelationFacts, taskStatsSynced, relationStatsSynced, taskProcRanked, relationProcRanked, profileUpdated);
    }

    private int archiveLowQualityMemory() {
        try {
            return offlineLearningMapper.archiveLowQualityMemory();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int mergeDuplicateTaskFacts() {
        try {
            return offlineLearningMapper.mergeDuplicateTaskFacts();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int mergeDuplicateRelationalFacts() {
        try {
            return offlineLearningMapper.mergeDuplicateRelationalFacts();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int rankTaskProcedures() {
        try {
            return offlineLearningMapper.rankTaskProcedures();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int rankRelationalProcedures() {
        try {
            return offlineLearningMapper.rankRelationalProcedures();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int syncTaskProcedureStatsFromEpisodes() {
        try {
            return offlineLearningMapper.syncTaskProcedureStatsFromEpisodes();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int syncRelationalProcedureStatsFromEpisodes() {
        try {
            return offlineLearningMapper.syncRelationalProcedureStatsFromEpisodes();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private int calibrateRelationalProfileScores() {
        try {
            return offlineLearningMapper.calibrateRelationalProfileScores();
        } catch (Exception ignore) {
            return 0;
        }
    }
}
