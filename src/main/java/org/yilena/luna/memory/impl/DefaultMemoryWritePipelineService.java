package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.MemoryWriteMapper;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultMemoryWritePipelineService implements MemoryWritePipelineService {

    private final MemoryWriteMapper memoryWriteMapper;
    private final ObjectMapper objectMapper;
    private final MemoryWritePolicyGate memoryWritePolicyGate;
    private final RuntimeAuditService runtimeAuditService;

    @Override
    public void writeAfterTurn(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        insertMessage(sessionId, "USER", userInput);
        insertMessage(sessionId, "ASSISTANT", assistantReply);
        updateSessionState(sessionId, contextPackage);
        upsertTaskWorkingMemory(sessionId, userInput, assistantReply, contextPackage);
        RelationalWorkingSnapshot relationalSnapshot = resolveRelationalWorkingSnapshot(userInput, contextPackage);
        upsertRelationalWorkingMemory(sessionId, contextPackage, relationalSnapshot);
        MemoryWritePolicyGate.GateContext gateContext = memoryWritePolicyGate.buildContext(sessionId, contextPackage);
        extractAndPersistSemanticFacts(sessionId, userInput, assistantReply, contextPackage, relationalSnapshot, gateContext);
        upsertRelationalLongTermMemory(sessionId, userInput, contextPackage, relationalSnapshot, gateContext);
        buildEpisodes(sessionId, userInput, assistantReply, contextPackage, gateContext);
        reflectAndMineProcedures(sessionId, userInput, assistantReply, contextPackage, gateContext);
        updateProcedureStatistics(userInput, contextPackage);
        refreshWorkingMemoryRegistry(sessionId, contextPackage, gateContext);
    }

    private void insertMessage(String sessionId, String role, String content) {
        try {
            memoryWriteMapper.insertMessage(sessionId, role, content);
        } catch (Exception ignore) {
        }
    }

    private void updateSessionState(String sessionId, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        try {
            memoryWriteMapper.updateSessionState(
                    sessionId,
                    taskState != null ? taskState.name() : TaskRuntimeState.UNDERSTANDING.name(),
                    relationalState != null ? relationalState.name() : RelationalRuntimeState.LIGHT_CHAT.name()
            );
        } catch (Exception ignore) {
        }
    }

    private void upsertTaskWorkingMemory(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        List<String> constraints = extractTaskConstraints(lower);
        List<String> successCriteria = extractSuccessCriteria(lower);
        List<String> entities = extractKeyEntities(lower);
        List<String> questions = extractQuestions(userInput);
        List<String> risks = extractTaskRisks(lower);
        try {
            memoryWriteMapper.upsertTaskWorking(
                    sessionId,
                    userInput,
                    summarize(userInput, 260),
                    toJson(Map.of("source", "memory_write_pipeline")),
                    toJson(constraints),
                    toJson(successCriteria),
                    toJson(List.of()),
                    toJson(entities),
                    toJson(List.of()),
                    toJson(questions),
                    toJson(risks),
                    null,
                    null,
                    toJson(List.of()),
                    summarize(assistantReply, 260)
            );
            upsertTaskWorkingSlots(sessionId, constraints, successCriteria, entities, questions, risks);
        } catch (Exception ignore) {
        }
    }

    private void upsertTaskWorkingSlots(String sessionId,
                                        List<String> constraints,
                                        List<String> successCriteria,
                                        List<String> entities,
                                        List<String> questions,
                                        List<String> risks) {
        upsertSlot(sessionId, "constraints", "CONSTRAINT", constraints, 90);
        upsertSlot(sessionId, "success_criteria", "SUCCESS_CRITERIA", successCriteria, 90);
        upsertSlot(sessionId, "key_entities", "ENTITY", entities, 70);
        upsertSlot(sessionId, "unresolved_questions", "QUESTION", questions, 80);
        upsertSlot(sessionId, "risks", "RISK", risks, 85);
    }

    private void upsertSlot(String sessionId, String slotName, String slotType, Object value, int priority) {
        try {
            memoryWriteMapper.upsertTaskWorkingSlot(
                    sessionId,
                    slotName,
                    slotType,
                    toJson(value),
                    priority,
                    "MEMORY_WRITE_PIPELINE",
                    sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void upsertRelationalWorkingMemory(String sessionId,
                                               StructuredContextPackage contextPackage,
                                               RelationalWorkingSnapshot snapshot) {
        String relationalState = contextPackage != null && contextPackage.getRelationalState() != null
                ? contextPackage.getRelationalState().name()
                : RelationalRuntimeState.LIGHT_CHAT.name();
        try {
            memoryWriteMapper.upsertRelationalWorking(
                    sessionId,
                    relationalState,
                    snapshot.inferredEmotion(),
                    snapshot.emotionConfidence(),
                    snapshot.desiredTone(),
                    snapshot.supportIntent(),
                    snapshot.interactionGoal(),
                    toJson(snapshot.cautionFlags()),
                    toJson(snapshot.bondSignals()),
                    toJson(snapshot.sensitiveSignals())
            );
        } catch (Exception ignore) {
        }
    }

    private RelationalWorkingSnapshot resolveRelationalWorkingSnapshot(String userInput, StructuredContextPackage contextPackage) {
        Map<String, Object> relationalContext = contextPackage == null ? Map.of() : asMap(contextPackage.getRelationalContext());
        Map<String, Object> working = asMap(relationalContext.get("working_memory"));
        Map<String, Object> profile = asMap(relationalContext.get("profile"));
        Map<String, Object> socialDraft = resolveSocialDraft(contextPackage);

        String relationalState = contextPackage != null && contextPackage.getRelationalState() != null
                ? contextPackage.getRelationalState().name()
                : RelationalRuntimeState.LIGHT_CHAT.name();

        String inferredEmotion = firstNonBlank(
                asText(socialDraft.get("inferred_emotion")),
                asText(working.get("inferred_emotion")),
                inferEmotionFromInput(userInput, relationalState)
        );
        double emotionConfidence = bounded(
                firstPositive(
                        asDouble(socialDraft.get("emotion_confidence"), 0.0),
                        asDouble(working.get("emotion_confidence"), 0.0),
                        inferEmotionConfidence(userInput, inferredEmotion)
                ),
                0.35,
                0.98
        );
        String desiredTone = firstNonBlank(
                asText(socialDraft.get("recommended_tone")),
                asText(working.get("desired_tone")),
                asText(profile.get("preferred_tone")),
                inferTone(relationalState)
        );
        String supportIntent = firstNonBlank(
                asText(socialDraft.get("support_intent")),
                asText(working.get("support_intent")),
                inferSupportIntent(userInput, relationalState)
        );
        String interactionGoal = firstNonBlank(
                asText(socialDraft.get("interaction_goal")),
                asText(working.get("interaction_goal")),
                inferInteractionGoal(relationalState)
        );

        List<String> cautionFlags = mergeSignals(
                asStringList(socialDraft.get("boundary_hints")),
                extractSignalsSafe(asText(userInput), "别", "不要", "不喜欢", "敏感", "冒犯", "boundary")
        );
        List<String> bondSignals = mergeSignals(
                asStringList(socialDraft.get("closing_style")),
                extractSignalsSafe(asText(userInput), "谢谢", "一起", "陪我", "信任", "太好了", "庆祝")
        );
        List<String> sensitiveSignals = mergeSignals(
                asStringList(relationalContext.get("boundary_rules")),
                extractSignalsSafe(asText(userInput), "难受", "焦虑", "崩溃", "撑不住", "不舒服")
        );

        return new RelationalWorkingSnapshot(
                inferredEmotion,
                emotionConfidence,
                desiredTone,
                supportIntent,
                interactionGoal,
                cautionFlags,
                bondSignals,
                sensitiveSignals
        );
    }

    private String inferTone(String relationalState) {
        if ("EMOTIONAL_SUPPORT".equals(relationalState) || "FRAGILE_MOMENT".equals(relationalState)) {
            return "soft_and_calm";
        }
        if ("CELEBRATING".equals(relationalState)) {
            return "warm_and_positive";
        }
        if ("REPAIRING".equals(relationalState)) {
            return "careful_and_humble";
        }
        return "clear_and_friendly";
    }

    private String inferSupportIntent(String userInput, String relationalState) {
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "先听", "listen", "先别给方案", "安慰", "陪我")) {
            return "listen_first";
        }
        if ("REPAIRING".equals(relationalState)) {
            return "repair_alignment";
        }
        if ("CELEBRATING".equals(relationalState)) {
            return "amplify_positive";
        }
        if ("EMOTIONAL_SUPPORT".equals(relationalState) || "FRAGILE_MOMENT".equals(relationalState)) {
            return "stabilize_emotion";
        }
        return "task_forward";
    }

    private String inferInteractionGoal(String relationalState) {
        if ("REPAIRING".equals(relationalState)) {
            return "confirm_alignment";
        }
        if ("EMOTIONAL_SUPPORT".equals(relationalState) || "FRAGILE_MOMENT".equals(relationalState)) {
            return "stabilize_then_progress";
        }
        if ("CELEBRATING".equals(relationalState)) {
            return "anchor_momentum";
        }
        return "solve_task";
    }

    private String inferEmotionFromInput(String userInput, String relationalState) {
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "焦虑", "紧张", "anxious", "压力")) {
            return "anxious";
        }
        if (containsAny(lower, "崩溃", "撑不住", "难受", "sad", "down")) {
            return "sad";
        }
        if (containsAny(lower, "开心", "太好了", "celebrate", "great")) {
            return "positive";
        }
        if ("REPAIRING".equals(relationalState)) {
            return "uneasy";
        }
        if ("EMOTIONAL_SUPPORT".equals(relationalState) || "FRAGILE_MOMENT".equals(relationalState)) {
            return "tired";
        }
        return "calm";
    }

    private double inferEmotionConfidence(String userInput, String inferredEmotion) {
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "我很", "真的", "非常", "特别", "today i feel")) {
            return 0.83;
        }
        if (containsAny(lower, "可能", "有点", "maybe", "perhaps")) {
            return 0.58;
        }
        if ("calm".equals(inferredEmotion)) {
            return 0.50;
        }
        return 0.70;
    }

    private void extractAndPersistSemanticFacts(String sessionId,
                                                String userInput,
                                                String assistantReply,
                                                StructuredContextPackage contextPackage,
                                                RelationalWorkingSnapshot relationalSnapshot,
                                                MemoryWritePolicyGate.GateContext gateContext) {
        List<SemanticFactCandidate> candidates = new ArrayList<>();
        candidates.addAll(extractStructuredTaskSemanticFacts(userInput, assistantReply, contextPackage));
        candidates.addAll(extractStructuredRelationalFacts(userInput, contextPackage, relationalSnapshot));
        if (candidates.isEmpty()) {
            candidates.addAll(extractFallbackSemanticFacts(userInput));
        }

        Set<String> dedupe = new LinkedHashSet<>();
        boolean taskDomainOpen = hasTaskPerceptualSignals(contextPackage, userInput, assistantReply);
        boolean relationDomainOpen = hasRelationalPerceptualSignals(contextPackage, userInput, relationalSnapshot.supportIntent());
        for (SemanticFactCandidate candidate : candidates) {
            if (candidate == null || candidate.factValue().isBlank()) {
                continue;
            }
            if (shouldHardDenyLongTermCandidate(candidate.sourceType(), candidate.factValue(), contextPackage)) {
                auditGateRejection(
                        sessionId,
                        contextPackage,
                        "SEMANTIC_CANDIDATE",
                        candidate.sourceType(),
                        new MemoryWritePolicyGate.GateDecision(false, "HARD_DENY_INTERMEDIATE_OR_PENDING", candidate.confidence())
                );
                continue;
            }
            if ("TASK".equals(candidate.domain()) && !taskDomainOpen) {
                continue;
            }
            if ("RELATION".equals(candidate.domain()) && !relationDomainOpen) {
                continue;
            }
            String dedupeKey = (candidate.domain() + "|" + candidate.factType() + "|" + candidate.factKey() + "|" + candidate.factValue()).toLowerCase(Locale.ROOT);
            if (!dedupe.add(dedupeKey)) {
                continue;
            }
            if ("TASK".equals(candidate.domain())) {
                insertTaskSemanticFact(
                        sessionId,
                        candidate.factType(),
                        candidate.factKey(),
                        candidate.factValue(),
                        candidate.sourceType(),
                        candidate.confidence(),
                        candidate.stability(),
                        gateContext,
                        contextPackage
                );
            } else {
                insertRelationalSemanticFact(
                        sessionId,
                        candidate.factType(),
                        candidate.factKey(),
                        candidate.factValue(),
                        candidate.sourceType(),
                        candidate.confidence(),
                        candidate.stability(),
                        gateContext,
                        contextPackage
                );
            }
        }
    }

    private List<SemanticFactCandidate> extractStructuredTaskSemanticFacts(String userInput,
                                                                           String assistantReply,
                                                                           StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return List.of();
        }
        Map<String, Object> taskContext = asMap(contextPackage.getTaskContext());
        List<Map<String, Object>> taskFacts = asMapList(taskContext.get("task_facts"));
        List<SemanticFactCandidate> out = new ArrayList<>();
        for (Map<String, Object> fact : taskFacts) {
            String value = summarize(asText(fact.get("fact_value_text")), 220);
            if (value.isBlank() || !isSupportedByCurrentTurn(value, userInput, assistantReply)) {
                continue;
            }
            out.add(new SemanticFactCandidate(
                    "TASK",
                    firstNonBlank(asText(fact.get("fact_type")).toUpperCase(Locale.ROOT), "DOMAIN_FACT"),
                    firstNonBlank(asText(fact.get("fact_key")), "context_reinforced_fact"),
                    value,
                    "CONTEXT_REINFORCED",
                    bounded(asDouble(fact.get("confidence_score"), 0.74) + 0.04, 0.45, 0.98),
                    bounded(asDouble(fact.get("stability_score"), 0.74) + 0.05, 0.40, 0.98)
            ));
        }
        return out;
    }

    private boolean hasTaskPerceptualSignals(StructuredContextPackage contextPackage, String userInput, String assistantReply) {
        if (containsAny((asText(userInput) + " " + asText(assistantReply)).toLowerCase(Locale.ROOT),
                "plan", "execute", "fix", "实现", "修复", "执行")) {
            return true;
        }
        Map<String, Object> taskContext = contextPackage == null ? Map.of() : asMap(contextPackage.getTaskContext());
        List<Map<String, Object>> taskBuffer = asMapList(taskContext.get("task_perceptual_buffer"));
        return taskBuffer != null && !taskBuffer.isEmpty();
    }

    private boolean hasRelationalPerceptualSignals(StructuredContextPackage contextPackage, String userInput, String supportIntent) {
        if (containsAny((asText(userInput) + " " + asText(supportIntent)).toLowerCase(Locale.ROOT),
                "support", "listen", "boundary", "comfort", "焦虑", "难受", "边界", "陪我", "先听")) {
            return true;
        }
        Map<String, Object> relationalContext = contextPackage == null ? Map.of() : asMap(contextPackage.getRelationalContext());
        List<Map<String, Object>> relationBuffer = asMapList(relationalContext.get("relational_perceptual_buffer"));
        return relationBuffer != null && !relationBuffer.isEmpty();
    }

    private List<SemanticFactCandidate> extractStructuredRelationalFacts(String userInput,
                                                                         StructuredContextPackage contextPackage,
                                                                         RelationalWorkingSnapshot snapshot) {
        if (contextPackage == null) {
            return List.of();
        }
        Map<String, Object> relationalContext = asMap(contextPackage.getRelationalContext());
        Map<String, Object> profile = asMap(relationalContext.get("profile"));
        List<Map<String, Object>> boundaryRules = asMapList(relationalContext.get("boundary_rules"));
        List<SemanticFactCandidate> out = new ArrayList<>();

        for (Map<String, Object> rule : boundaryRules) {
            String ruleKey = firstNonBlank(asText(rule.get("rule_key")), "boundary_rule");
            String ruleValue = summarize(asText(rule.get("rule_value")), 160);
            if (ruleValue.isBlank()) {
                continue;
            }
            if (isSupportedByCurrentTurn(ruleValue, userInput, snapshot.supportIntent())) {
                out.add(new SemanticFactCandidate(
                        "RELATION",
                        "BOUNDARY",
                        ruleKey,
                        ruleValue,
                        "CONTEXT_RULE",
                        bounded(asDouble(rule.get("confidence_score"), 0.78), 0.45, 0.98),
                        0.86
                ));
            }
        }

        String preferredTone = asText(profile.get("preferred_tone"));
        if (!preferredTone.isBlank() && isSupportedByCurrentTurn(preferredTone, userInput, snapshot.desiredTone())) {
            out.add(new SemanticFactCandidate(
                    "RELATION",
                    "INTERACTION_STYLE",
                    "preferred_tone",
                    preferredTone,
                    "PROFILE_REINFORCED",
                    0.78,
                    0.80
            ));
        }

        if (!snapshot.supportIntent().isBlank() && containsAny(snapshot.supportIntent(), "listen", "repair", "stabilize")) {
            out.add(new SemanticFactCandidate(
                    "RELATION",
                    "SUPPORT_STYLE",
                    "dynamic_support_intent",
                    snapshot.supportIntent(),
                    "SOCIAL_DRAFT",
                    bounded(snapshot.emotionConfidence() * 0.90, 0.45, 0.95),
                    0.62
            ));
        }

        return out;
    }

    private List<SemanticFactCandidate> extractFallbackSemanticFacts(String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        if (text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<SemanticFactCandidate> out = new ArrayList<>();
        if (containsAny(lower, "以后", "从现在", "默认", "prefer", "always", "请用", "输出用")) {
            out.add(new SemanticFactCandidate("TASK", "PREFERENCE", "explicit_output_preference", summarize(text, 220), "USER_INPUT", 0.84, 0.83));
        }
        if (containsAny(lower, "我在做", "我们做", "行业", "业务", "b 端", "b端", "saas")) {
            out.add(new SemanticFactCandidate("TASK", "DOMAIN_FACT", "explicit_domain_fact", summarize(text, 220), "USER_INPUT", 0.80, 0.86));
        }
        if (containsAny(lower, "default", "prefer", "markdown", "format", "style", "偏好", "默认")) {
            out.add(new SemanticFactCandidate("TASK", "PREFERENCE", "auto_extracted_task_pref", summarize(text, 220), "USER_INPUT", 0.68, 0.58));
        }
        if (containsAny(lower, "do not call me", "don't lecture", "uncomfortable", "need support first", "别叫我", "先别给方案", "不喜欢说教")) {
            out.add(new SemanticFactCandidate("RELATION", "BOUNDARY", "auto_extracted_relation_boundary", summarize(text, 220), "USER_INPUT", 0.82, 0.79));
        }
        if (containsAny(lower, "先听", "先安慰", "先陪我", "listen first", "comfort first")) {
            out.add(new SemanticFactCandidate("RELATION", "SUPPORT_STYLE", "explicit_support_style", summarize(text, 220), "USER_INPUT", 0.80, 0.72));
        }
        return out;
    }

    private void upsertRelationalLongTermMemory(String sessionId,
                                                String userInput,
                                                StructuredContextPackage contextPackage,
                                                RelationalWorkingSnapshot snapshot,
                                                MemoryWritePolicyGate.GateContext gateContext) {
        MemoryWritePolicyGate.GateDecision profileDecision = memoryWritePolicyGate.evaluateLongTermWrite(
                gateContext,
                "RELATIONAL_PROFILE",
                "SOCIAL_DRAFT",
                snapshot == null ? 0.0 : snapshot.emotionConfidence(),
                userInput
        );
        if (!profileDecision.allow()) {
            auditGateRejection(sessionId, contextPackage, "RELATIONAL_PROFILE", "SOCIAL_DRAFT", profileDecision);
            return;
        }
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        Map<String, Object> relationalContext = contextPackage == null ? Map.of() : asMap(contextPackage.getRelationalContext());
        Map<String, Object> profile = asMap(relationalContext.get("profile"));
        String preferredTone = firstNonBlank(asText(profile.get("preferred_tone")), snapshot.desiredTone());
        String supportStyle = firstNonBlank(asText(profile.get("emotional_support_style")), snapshot.supportIntent());
        double trustScore = bounded(0.52 + snapshot.emotionConfidence() * 0.38 + ("REPAIRING".equals(snapshot.interactionGoal()) ? -0.06 : 0.04), 0.35, 0.96);
        double intimacyScore = bounded(0.48 + snapshot.emotionConfidence() * 0.34 + (containsAny(lower, "一起", "陪我", "信任", "long term") ? 0.08 : 0.0), 0.30, 0.95);
        try {
            memoryWriteMapper.upsertRelationalProfile(
                    sessionId,
                    contextPackage != null && contextPackage.getRelationalState() != null ? contextPackage.getRelationalState().name() : "FAMILIARIZING",
                    firstNonBlank(asText(profile.get("preferred_name")), ""),
                    firstNonBlank(preferredTone, containsAny(lower, "简短", "直接") ? "concise_direct" : "clear_and_friendly"),
                    firstNonBlank(supportStyle, containsAny(lower, "先别给方案", "先听我说") ? "listen_first" : "balanced"),
                    "neutral",
                    "medium",
                    toJson(Map.of("source", "online_pipeline", "interaction_goal", snapshot.interactionGoal())),
                    toJson(Map.of("source", "online_pipeline", "caution_flags", snapshot.cautionFlags())),
                    toJson(snapshot.sensitiveSignals()),
                    toJson(extractComfortTriggers(lower)),
                    toJson(extractNoGoPatterns(lower)),
                    trustScore,
                    intimacyScore
            );
        } catch (Exception ignore) {
        }

        try {
            memoryWriteMapper.upsertEmotionalBaseline(
                    sessionId,
                    containsAny(lower, "急", "快点") ? "urgent" : "neutral",
                    toJson(extractSignals(lower, "焦虑", "紧张", "来不及")),
                    toJson(extractSignals(lower, "很累", "没力气", "崩溃")),
                    toJson(extractSignals(lower, "难受", "低落", "失望")),
                    toJson(extractComfortTriggers(lower)),
                    toJson(List.of("small_steps")),
                    bounded(0.46 + snapshot.emotionConfidence() * 0.40, 0.35, 0.92)
            );
        } catch (Exception ignore) {
        }

        tryInsertBoundaryRule(sessionId, lower, "ADDRESS", "name_usage", "别叫我");
        tryInsertBoundaryRule(sessionId, lower, "EMOTIONAL", "avoid_preachy_tone", "不喜欢说教");
        tryInsertBoundaryRule(sessionId, lower, "PACE", "listen_before_advice", "先别给方案");
    }

    private void insertTaskSemanticFact(String sessionId,
                                        String factType,
                                        String factKey,
                                        String factValue,
                                        String sourceType,
                                        double confidence,
                                        double stability,
                                        MemoryWritePolicyGate.GateContext gateContext,
                                        StructuredContextPackage contextPackage) {
        MemoryWritePolicyGate.GateDecision decision = memoryWritePolicyGate.evaluateLongTermWrite(
                gateContext,
                "TASK_SEMANTIC",
                sourceType,
                confidence,
                factValue
        );
        if (!decision.allow()) {
            auditGateRejection(sessionId, contextPackage, "TASK_SEMANTIC", sourceType, decision);
            return;
        }
        try {
            double boundedConfidence = bounded(confidence, 0.20, 0.99);
            double boundedStability = bounded(stability, 0.20, 0.99);
            int revised = memoryWriteMapper.reviseTaskSemanticFact(
                    sessionId,
                    factType,
                    factKey,
                    factValue,
                    sourceType,
                    sessionId,
                    boundedConfidence,
                    boundedStability
            );
            if (revised <= 0) {
                memoryWriteMapper.insertTaskSemanticFact(
                        sessionId,
                        factType,
                        factKey,
                        factValue,
                        sourceType,
                        sessionId,
                        boundedConfidence,
                        boundedStability
                );
            }
        } catch (Exception ignore) {
        }
    }

    private void insertRelationalSemanticFact(String sessionId,
                                              String factType,
                                              String factKey,
                                              String factValue,
                                              String sourceType,
                                              double confidence,
                                              double stability,
                                              MemoryWritePolicyGate.GateContext gateContext,
                                              StructuredContextPackage contextPackage) {
        MemoryWritePolicyGate.GateDecision decision = memoryWritePolicyGate.evaluateLongTermWrite(
                gateContext,
                "RELATIONAL_SEMANTIC",
                sourceType,
                confidence,
                factValue
        );
        if (!decision.allow()) {
            auditGateRejection(sessionId, contextPackage, "RELATIONAL_SEMANTIC", sourceType, decision);
            return;
        }
        try {
            double boundedConfidence = bounded(confidence, 0.20, 0.99);
            double boundedStability = bounded(stability, 0.20, 0.99);
            int revised = memoryWriteMapper.reviseRelationalSemanticFact(
                    sessionId,
                    factType,
                    factKey,
                    factValue,
                    sourceType,
                    sessionId,
                    boundedConfidence,
                    boundedStability
            );
            if (revised <= 0) {
                memoryWriteMapper.insertRelationalSemanticFact(
                        sessionId,
                        factType,
                        factKey,
                        factValue,
                        sourceType,
                        sessionId,
                        boundedConfidence,
                        boundedStability
                );
            }
        } catch (Exception ignore) {
        }
    }

    private void buildEpisodes(String sessionId,
                               String userInput,
                               String assistantReply,
                               StructuredContextPackage contextPackage,
                               MemoryWritePolicyGate.GateContext gateContext) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        MemoryWritePolicyGate.GateDecision episodeDecision = memoryWritePolicyGate.evaluateLongTermWrite(
                gateContext,
                "EPISODE",
                "SUMMARY_SNAPSHOT",
                0.70,
                summarize(userInput, 180) + " " + summarize(assistantReply, 180)
        );
        if (!episodeDecision.allow()) {
            auditGateRejection(sessionId, contextPackage, "EPISODE", "SUMMARY_SNAPSHOT", episodeDecision);
            return;
        }

        if (taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REPORTING) {
            try {
                memoryWriteMapper.insertTaskEpisode(
                        sessionId,
                        taskState == TaskRuntimeState.FAILED ? "FAILURE" : "SUCCESS",
                        summarize(userInput, 96),
                        summarize(userInput, 300),
                        summarize(assistantReply, 400),
                        taskState.name(),
                        taskState == TaskRuntimeState.FAILED ? "needs_replan" : "successful_turn"
                );
                Long episodeId = memoryWriteMapper.selectLatestTaskEpisodeId(sessionId);
                writeEpisodeSteps(episodeId, userInput, assistantReply, taskState);
            } catch (Exception ignore) {
            }
        }

        if (relationState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationState == RelationalRuntimeState.REPAIRING
                || relationState == RelationalRuntimeState.CELEBRATING
                || relationState == RelationalRuntimeState.FRAGILE_MOMENT) {
            try {
                memoryWriteMapper.insertRelationalEpisode(
                        sessionId,
                        relationState == RelationalRuntimeState.CELEBRATING ? "CELEBRATION" : (relationState == RelationalRuntimeState.REPAIRING ? "REPAIR" : "COMFORT"),
                        summarize(userInput, 96),
                        summarize(assistantReply, 320),
                        relationState.name(),
                        relationState.name(),
                        inferTone(relationState.name())
                );
            } catch (Exception ignore) {
            }
        }
    }

    private void writeEpisodeSteps(Long episodeId, String userInput, String assistantReply, TaskRuntimeState taskState) {
        if (episodeId == null) {
            return;
        }
        insertEpisodeStep(episodeId, 1, "USER_INPUT", "User Request", summarize(userInput, 400), Map.of());
        insertEpisodeStep(episodeId, 2, "ASSISTANT_OUTPUT", "Assistant Reply", summarize(assistantReply, 500), Map.of("task_state", taskState.name()));
    }

    private void insertEpisodeStep(Long episodeId,
                                   int order,
                                   String stepType,
                                   String title,
                                   String content,
                                   Map<String, Object> payload) {
        try {
            memoryWriteMapper.insertTaskEpisodeStep(
                    episodeId,
                    order,
                    stepType,
                    title,
                    content,
                    toJson(payload)
            );
        } catch (Exception ignore) {
        }
    }

    private void reflectAndMineProcedures(String sessionId,
                                          String userInput,
                                          String assistantReply,
                                          StructuredContextPackage contextPackage,
                                          MemoryWritePolicyGate.GateContext gateContext) {
        if (contextPackage == null) {
            return;
        }
        MemoryWritePolicyGate.GateDecision procedureDecision = memoryWritePolicyGate.evaluateLongTermWrite(
                gateContext,
                "PROCEDURE",
                "SUMMARY_SNAPSHOT",
                0.66,
                summarize(userInput, 180) + " " + summarize(assistantReply, 180)
        );
        if (!procedureDecision.allow()) {
            auditGateRejection(sessionId, contextPackage, "PROCEDURE", "SUMMARY_SNAPSHOT", procedureDecision);
            return;
        }
        LearningSignal signal = deriveLearningSignal(contextPackage, userInput, assistantReply);
        ensureTaskPlanningProcedure();
        ensureTaskExecutionProcedure();
        ensureRelationalSupportProcedure();

        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        if (signal.taskReflectionRequired(taskState)) {
            writeTaskReflection(sessionId, userInput, assistantReply, taskState, signal.taskReason());
            ensureTaskRecoveryProcedure();
        }
        if (signal.relationReflectionRequired(relationState)) {
            writeRelationalReflection(sessionId, userInput, assistantReply, signal.relationReason());
            ensureRelationalRepairProcedure();
        }
    }

    private void updateProcedureStatistics(String userInput, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        boolean taskSuccess = taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.REPORTING;
        boolean taskFailure = taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REFLECTING;
        boolean planningPhase = taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING;
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);

        try {
            memoryWriteMapper.updateTaskExecutionProcedureStats(taskSuccess ? 1 : 0, taskFailure ? 1 : 0);
            if (planningPhase) {
                memoryWriteMapper.updateTaskPlanningProcedureStats(taskFailure ? 0 : 1, taskFailure ? 1 : 0);
            }
            if (taskFailure) {
                memoryWriteMapper.incrementTaskFailureRecovery();
            }
        } catch (Exception ignore) {
        }

        boolean relationEngaged = relationState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationState == RelationalRuntimeState.REPAIRING
                || relationState == RelationalRuntimeState.CELEBRATING;
        if (!relationEngaged) {
            return;
        }
        boolean relationFailure = relationState == RelationalRuntimeState.REPAIRING
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way", "你没懂我", "被冒犯", "不舒服");
        try {
            memoryWriteMapper.updateRelationalSupportProcedureStats(relationFailure ? 0 : 1, relationFailure ? 1 : 0);
            if (relationFailure) {
                memoryWriteMapper.incrementRelationalRepair();
            }
        } catch (Exception ignore) {
        }
    }

    private void writeTaskReflection(String sessionId,
                                     String userInput,
                                     String assistantReply,
                                     TaskRuntimeState taskState,
                                     String reason) {
        try {
            memoryWriteMapper.insertTaskReflection(
                    sessionId,
                    taskState.name(),
                    reason == null || reason.isBlank() ? "task_state_trigger" : reason,
                    summarize(userInput, 220),
                    "execution_quality_risk",
                    summarize(assistantReply, 220)
            );
        } catch (Exception ignore) {
        }
    }

    private void writeRelationalReflection(String sessionId,
                                           String userInput,
                                           String assistantReply,
                                           String reason) {
        try {
            memoryWriteMapper.insertRelationalReflection(
                    sessionId,
                    summarize(userInput, 220),
                    reason == null || reason.isBlank() ? "tone_or_understanding_gap" : reason,
                    summarize(assistantReply, 220)
            );
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskPlanningProcedure() {
        try {
            memoryWriteMapper.ensureTaskPlanningProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskExecutionProcedure() {
        try {
            memoryWriteMapper.ensureTaskExecutionProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskRecoveryProcedure() {
        try {
            memoryWriteMapper.ensureTaskRecoveryProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureRelationalSupportProcedure() {
        try {
            memoryWriteMapper.ensureRelationalSupportProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureRelationalRepairProcedure() {
        try {
            memoryWriteMapper.ensureRelationalRepairProcedure();
        } catch (Exception ignore) {
        }
    }

    private void refreshWorkingMemoryRegistry(String sessionId,
                                              StructuredContextPackage contextPackage,
                                              MemoryWritePolicyGate.GateContext gateContext) {
        try {
            memoryWriteMapper.refreshTaskWorkingRegistry(sessionId);
            memoryWriteMapper.refreshRelationalWorkingRegistry(sessionId);
            if (memoryWritePolicyGate.shouldWriteOnlyShortTerm(gateContext == null ? null : gateContext.taskState())) {
                auditGateRejection(
                        sessionId,
                        contextPackage,
                        "LONG_TERM_REGISTRY",
                        "SUMMARY_SNAPSHOT",
                        new MemoryWritePolicyGate.GateDecision(false, "TASK_STATE_SHORT_TERM_ONLY", 0.0)
                );
                return;
            }
            memoryWriteMapper.refreshTaskSemanticRegistry(sessionId);
            memoryWriteMapper.refreshRelationalSemanticRegistry(sessionId);
            memoryWriteMapper.refreshTaskEpisodeRegistry(sessionId);
            memoryWriteMapper.refreshRelationalEpisodeRegistry(sessionId);
            memoryWriteMapper.refreshTaskProcedureRegistry(sessionId);
            memoryWriteMapper.refreshRelationalProcedureRegistry(sessionId);
            memoryWriteMapper.refreshRelationalProfileRegistry(sessionId);
            memoryWriteMapper.refreshEmotionalBaselineRegistry(sessionId);
            memoryWriteMapper.refreshBoundaryRuleRegistry(sessionId);
            memoryWriteMapper.upsertWorkingDerivedRelations(sessionId);
            memoryWriteMapper.upsertWorkingSupportRelations(sessionId);
            memoryWriteMapper.upsertTaskContradictionRelations(sessionId);
            memoryWriteMapper.upsertRelationalContradictionRelations(sessionId);
            memoryWriteMapper.upsertEpisodeGeneralizationRelations(sessionId);
            memoryWriteMapper.upsertEpisodeSummaryRelations(sessionId);
        } catch (Exception ignore) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }

    private String asText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private Map<String, Object> resolveSocialDraft(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getPromptPolicy() == null) {
            return Map.of();
        }
        Object raw = contextPackage.getPromptPolicy().get("social_draft");
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) map;
            return out;
        }
        return Map.of();
    }

    private List<String> mergeSignals(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList());
        }
        if (second != null) {
            merged.addAll(second.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList());
        }
        return merged.stream().toList();
    }

    private List<String> asStringList(Object value) {
        if (value instanceof String text) {
            if (text.isBlank()) {
                return List.of();
            }
            return List.of(text.trim());
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                if (item instanceof Map<?, ?> map) {
                    String key = asText(map.get("rule_key"));
                    String val = asText(map.get("rule_value"));
                    String packed = (key + ":" + val).trim();
                    if (!packed.isBlank() && !":".equals(packed)) {
                        out.add(packed);
                    }
                    continue;
                }
                String text = asText(item);
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        }
        return List.of();
    }

    private List<String> extractSignalsSafe(String text, String... words) {
        return extractSignals(text == null ? "" : text.toLowerCase(Locale.ROOT), words);
    }

    private void auditGateRejection(String sessionId,
                                    StructuredContextPackage contextPackage,
                                    String targetType,
                                    String sourceType,
                                    MemoryWritePolicyGate.GateDecision decision) {
        try {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "MEMORY_WRITE_POLICY_GATE_REJECT",
                    decision == null ? "UNKNOWN" : decision.reasonCode(),
                    toJson(Map.of(
                            "targetType", targetType == null ? "" : targetType,
                            "sourceType", sourceType == null ? "" : sourceType,
                            "reasonCode", decision == null ? "UNKNOWN" : decision.reasonCode(),
                            "confidence", decision == null ? 0.0 : decision.confidence()
                    ))
            );
        } catch (Exception ignore) {
        }
    }

    private boolean shouldHardDenyLongTermCandidate(String sourceType, String content, StructuredContextPackage contextPackage) {
        String normalizedSource = asText(sourceType).toUpperCase(Locale.ROOT);
        if ("PENDING_TOOL_RESULT".equals(normalizedSource)
                || "INTERMEDIATE_INFERENCE".equals(normalizedSource)
                || "UNVERIFIED_CONCLUSION".equals(normalizedSource)) {
            return true;
        }
        String lower = asText(content).toLowerCase(Locale.ROOT);
        if (containsAny(lower, "pending", "intermediate", "unverified", "to be confirmed", "temporary", "未确认", "中间结论", "待工具结果")) {
            return true;
        }
        if (contextPackage == null) {
            return false;
        }
        if (contextPackage.getTaskState() == TaskRuntimeState.WAITING_TOOL
                || contextPackage.getTaskState() == TaskRuntimeState.WAITING_APPROVAL
                || contextPackage.getTaskState() == TaskRuntimeState.WAITING_USER) {
            return true;
        }
        if (contextPackage.getToolState() != null) {
            String lastToolStatus = asText(contextPackage.getToolState().getLastToolStatus()).toLowerCase(Locale.ROOT);
            if (containsAny(lastToolStatus, "pending", "running", "waiting")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSupportedByCurrentTurn(String factValue, String userInput, String assistantReply) {
        String fact = asText(factValue).toLowerCase(Locale.ROOT);
        if (fact.isBlank()) {
            return false;
        }
        String merged = (asText(userInput) + " " + asText(assistantReply)).toLowerCase(Locale.ROOT);
        if (merged.isBlank()) {
            return false;
        }
        return merged.contains(fact)
                || fact.contains(merged)
                || containsAny(merged, splitToKeywords(fact));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private double firstPositive(double... values) {
        if (values == null) {
            return 0.0;
        }
        for (double value : values) {
            if (value > 0.0) {
                return value;
            }
        }
        return 0.0;
    }

    private double bounded(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private String[] splitToKeywords(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(text.split("[,;|\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return null;
        }
        Object session = contextPackage.getRuntime().get("session");
        if (!(session instanceof Map<?, ?> row)) {
            return null;
        }
        return toLong(row.get("current_plan_id"));
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return null;
        }
        Object working = contextPackage.getTaskContext().get("working_memory");
        if (!(working instanceof Map<?, ?> row)) {
            return null;
        }
        return toLong(row.get("active_node_id"));
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private record SemanticFactCandidate(String domain,
                                         String factType,
                                         String factKey,
                                         String factValue,
                                         String sourceType,
                                         double confidence,
                                         double stability) {
    }

    private record RelationalWorkingSnapshot(String inferredEmotion,
                                             double emotionConfidence,
                                             String desiredTone,
                                             String supportIntent,
                                             String interactionGoal,
                                             List<String> cautionFlags,
                                             List<String> bondSignals,
                                             List<String> sensitiveSignals) {
    }

    private LearningSignal deriveLearningSignal(StructuredContextPackage contextPackage, String userInput, String assistantReply) {
        String inputLower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        String replyLower = assistantReply == null ? "" : assistantReply.toLowerCase(Locale.ROOT);
        int recentToolFailures = countRecentToolFailures(contextPackage);
        boolean dissatisfaction = containsAny(inputLower, "你没懂", "不对", "不是这个", "offended", "you don't get me", "not this way");
        boolean explicitFailure = containsAny(inputLower, "失败", "报错", "error", "failed", "重试") || recentToolFailures > 0;
        boolean highCost = recentToolFailures >= 2 || containsAny(inputLower, "太慢", "花太久", "反复", "cost too high");
        boolean fragileSignal = containsAny(inputLower, "撑不住", "崩溃", "很难受", "fragile", "overwhelmed");
        boolean relationGap = dissatisfaction || containsAny(replyLower, "抱歉", "sorry");
        String taskReason = explicitFailure ? "runtime_failure_signal" : (highCost ? "high_cost_success_signal" : "");
        String relationReason = relationGap ? "relation_misalignment_signal" : (fragileSignal ? "fragile_support_signal" : "");
        return new LearningSignal(explicitFailure, highCost, relationGap, fragileSignal, taskReason, relationReason);
    }

    @SuppressWarnings("unchecked")
    private int countRecentToolFailures(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return 0;
        }
        Object raw = contextPackage.getRuntime().get("active_tool_results");
        if (!(raw instanceof List<?> list)) {
            return 0;
        }
        int failures = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Object statusObj = row.get("call_status");
            Object errorObj = row.get("error_message");
            String status = statusObj == null ? "" : String.valueOf(statusObj).toLowerCase(Locale.ROOT);
            String error = errorObj == null ? "" : String.valueOf(errorObj).toLowerCase(Locale.ROOT);
            if (containsAny(status, "failed", "error") || !error.isBlank()) {
                failures++;
            }
        }
        return failures;
    }

    private record LearningSignal(boolean taskFailureSignal,
                                  boolean highCostSignal,
                                  boolean relationGapSignal,
                                  boolean fragileSignal,
                                  String taskReason,
                                  String relationReason) {
        private boolean taskReflectionRequired(TaskRuntimeState taskState) {
            return taskState == TaskRuntimeState.FAILED
                    || taskState == TaskRuntimeState.REFLECTING
                    || taskFailureSignal
                    || (highCostSignal && (taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.REPORTING));
        }

        private boolean relationReflectionRequired(RelationalRuntimeState relationState) {
            return relationState == RelationalRuntimeState.REPAIRING
                    || relationState == RelationalRuntimeState.FRAGILE_MOMENT
                    || relationGapSignal
                    || fragileSignal;
        }
    }

    private List<String> extractTaskConstraints(String lower) {
        return extractSignals(lower, "不能", "先不", "不要", "必须", "截止");
    }

    private List<String> extractSuccessCriteria(String lower) {
        return extractSignals(lower, "完成", "通过", "上线", "交付");
    }

    private List<String> extractTaskRisks(String lower) {
        return extractSignals(lower, "风险", "担心", "来不及", "失败");
    }

    private List<String> extractKeyEntities(String lower) {
        return extractSignals(lower, "q1", "q2", "国内", "海外", "产品", "用户");
    }

    private List<String> extractQuestions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.contains("?") || text.contains("？") || text.contains("是否")) {
            return List.of(summarize(text, 160));
        }
        return List.of();
    }

    private List<String> extractComfortTriggers(String lower) {
        return extractSignals(lower, "陪我", "一起", "慢一点", "先听我说");
    }

    private List<String> extractNoGoPatterns(String lower) {
        return extractSignals(lower, "说教", "训我", "催促");
    }

    private List<String> extractSignals(String lower, String... words) {
        if (lower == null || lower.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                out.add(word);
            }
        }
        return out;
    }

    private void tryInsertBoundaryRule(String sessionId, String lower, String ruleType, String ruleKey, String triggerWord) {
        if (!lower.contains(triggerWord.toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            memoryWriteMapper.insertRelationalBoundaryRule(sessionId, ruleType, ruleKey, triggerWord, 0.82, "USER_INPUT");
        } catch (Exception ignore) {
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ignore) {
            return "[]";
        }
    }

    private String summarize(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

