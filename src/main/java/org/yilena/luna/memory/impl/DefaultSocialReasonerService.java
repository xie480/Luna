package org.yilena.luna.memory.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.memory.SocialReasonerService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultSocialReasonerService implements SocialReasonerService {

    @Override
    public Map<String, Object> buildRelationalDraft(String sessionId,
                                                    String userInput,
                                                    RelationalRuntimeState relationalState,
                                                    Map<String, Object> relationalContext) {
        Map<String, Object> working = asMap(relationalContext == null ? null : relationalContext.get("working_memory"));
        Map<String, Object> profile = asMap(relationalContext == null ? null : relationalContext.get("profile"));
        List<Map<String, Object>> semanticFacts = asList(relationalContext == null ? null : relationalContext.get("semantic_facts"));
        List<Map<String, Object>> boundaryRules = asList(relationalContext == null ? null : relationalContext.get("boundary_rules"));

        String inferredEmotion = valueOf(working.get("inferred_emotion"));
        double emotionConfidence = numberOf(working.get("emotion_confidence"), 0.0);
        String supportIntent = preferNonBlank(valueOf(working.get("support_intent")), inferSupportIntent(userInput, relationalState));
        String interactionGoal = preferNonBlank(valueOf(working.get("interaction_goal")), inferInteractionGoal(relationalState));
        String preferredTone = preferNonBlank(valueOf(working.get("desired_tone")), valueOf(profile.get("preferred_tone")));
        String supportStyle = preferNonBlank(valueOf(profile.get("emotional_support_style")), supportIntent);

        boolean highSensitivity = hasSensitiveSignals(boundaryRules, semanticFacts, profile);
        boolean lowEnergy = inferredEmotion.contains("tired") || inferredEmotion.contains("sad") || inferredEmotion.contains("anxious");

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("session_id", sessionId);
        draft.put("relational_state", relationalState.name());
        draft.put("inferred_emotion", inferredEmotion);
        draft.put("emotion_confidence", emotionConfidence);
        draft.put("support_intent", supportIntent);
        draft.put("interaction_goal", interactionGoal);
        draft.put("recommended_tone", decideTone(relationalState, preferredTone, supportStyle, highSensitivity, lowEnergy));
        draft.put("empathy_first", shouldEmpathyFirst(relationalState, supportIntent, emotionConfidence, lowEnergy));
        draft.put("question_intensity", decideQuestionIntensity(relationalState, highSensitivity, lowEnergy, supportIntent));
        draft.put("response_style", decideResponseStyle(relationalState, supportStyle, supportIntent, interactionGoal));
        draft.put("closing_style", decideClosingStyle(relationalState, interactionGoal, highSensitivity));
        draft.put("addressing_style", buildAddressingStyle(profile, semanticFacts));
        draft.put("boundary_hints", collectBoundaryHints(boundaryRules, semanticFacts));
        draft.put("source", "social_reasoner");
        draft.put("decision_basis", Map.of(
                "working_memory_used", !working.isEmpty(),
                "profile_used", !profile.isEmpty(),
                "semantic_fact_count", semanticFacts.size(),
                "boundary_rule_count", boundaryRules.size()
        ));
        return draft;
    }

    private String decideTone(RelationalRuntimeState state,
                              String preferredTone,
                              String supportStyle,
                              boolean highSensitivity,
                              boolean lowEnergy) {
        if (!preferredTone.isBlank()) {
            return preferredTone;
        }
        if (highSensitivity) {
            return "careful_and_gentle";
        }
        if (lowEnergy || supportStyle.contains("listen")) {
            return "soft_and_calm";
        }
        return switch (state) {
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "soft_and_calm";
            case REPAIRING -> "careful_and_humble";
            case CELEBRATING -> "warm_and_positive";
            case COMPANION_MODE, LIGHT_CHAT, DEEP_TALK -> "natural_and_companion";
            default -> "clear_and_friendly";
        };
    }

    private boolean shouldEmpathyFirst(RelationalRuntimeState state,
                                       String supportIntent,
                                       double emotionConfidence,
                                       boolean lowEnergy) {
        if (supportIntent.contains("listen") || supportIntent.contains("comfort")) {
            return true;
        }
        if (emotionConfidence >= 0.7 && lowEnergy) {
            return true;
        }
        return state == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || state == RelationalRuntimeState.FRAGILE_MOMENT
                || state == RelationalRuntimeState.REPAIRING;
    }

    private String decideQuestionIntensity(RelationalRuntimeState state,
                                           boolean highSensitivity,
                                           boolean lowEnergy,
                                           String supportIntent) {
        if (highSensitivity || supportIntent.contains("listen") || lowEnergy) {
            return "low";
        }
        return switch (state) {
            case FRAGILE_MOMENT, EMOTIONAL_SUPPORT -> "low";
            case REPAIRING -> "medium_low";
            case LIGHT_CHAT, COMPANION_MODE -> "medium";
            default -> "medium_high";
        };
    }

    private String decideResponseStyle(RelationalRuntimeState state,
                                       String supportStyle,
                                       String supportIntent,
                                       String interactionGoal) {
        if (supportStyle.contains("listen") || supportIntent.contains("listen")) {
            return "listen_then_small_steps";
        }
        if (interactionGoal.contains("stabilize")) {
            return "stabilize_then_minimum_plan";
        }
        return switch (state) {
            case EMOTIONAL_SUPPORT -> "listen_then_small_steps";
            case FRAGILE_MOMENT -> "stabilize_then_minimum_plan";
            case REPAIRING -> "acknowledge_then_realign";
            case CELEBRATING -> "affirm_then_next_goal";
            default -> "task_with_warmth";
        };
    }

    private String decideClosingStyle(RelationalRuntimeState state, String interactionGoal, boolean highSensitivity) {
        if (highSensitivity) {
            return "gentle_check_in";
        }
        if (interactionGoal.contains("confirm")) {
            return "confirm_alignment";
        }
        return switch (state) {
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "gentle_check_in";
            case REPAIRING -> "confirm_alignment";
            case CELEBRATING -> "positive_anchor";
            default -> "actionable_next_step";
        };
    }

    private String buildAddressingStyle(Map<String, Object> profile, List<Map<String, Object>> semanticFacts) {
        String preferredName = valueOf(profile.get("preferred_name"));
        if (!preferredName.isBlank()) {
            return preferredName;
        }
        for (Map<String, Object> fact : semanticFacts) {
            String factType = valueOf(fact.get("fact_type"));
            if (factType.contains("ADDRESS")) {
                return valueOf(fact.get("fact_value_text"));
            }
        }
        return "";
    }

    private List<String> collectBoundaryHints(List<Map<String, Object>> boundaryRules,
                                              List<Map<String, Object>> semanticFacts) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> rule : boundaryRules) {
            String key = valueOf(rule.get("rule_key"));
            String value = valueOf(rule.get("rule_value"));
            if (!key.isBlank() || !value.isBlank()) {
                out.add((key + ":" + value).trim());
            }
        }
        for (Map<String, Object> fact : semanticFacts) {
            String type = valueOf(fact.get("fact_type"));
            if (type.contains("BOUNDARY") || type.contains("SENSITIVE")) {
                out.add(valueOf(fact.get("fact_value_text")));
            }
        }
        return out;
    }

    private String inferSupportIntent(String userInput, RelationalRuntimeState state) {
        String text = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (containsAny(text, "listen", "先听", "别急", "难受", "焦虑")) {
            return "listen_first";
        }
        return switch (state) {
            case REPAIRING -> "repair_alignment";
            case CELEBRATING -> "amplify_positive";
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "stabilize_emotion";
            default -> "task_forward";
        };
    }

    private String inferInteractionGoal(RelationalRuntimeState state) {
        return switch (state) {
            case REPAIRING -> "confirm_alignment";
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "stabilize_then_progress";
            case CELEBRATING -> "anchor_momentum";
            default -> "solve_task";
        };
    }

    private boolean hasSensitiveSignals(List<Map<String, Object>> boundaryRules,
                                        List<Map<String, Object>> semanticFacts,
                                        Map<String, Object> profile) {
        if (!asList(profile.get("sensitive_topics_json")).isEmpty()) {
            return true;
        }
        if (!boundaryRules.isEmpty()) {
            return true;
        }
        for (Map<String, Object> fact : semanticFacts) {
            String type = valueOf(fact.get("fact_type"));
            if (type.contains("BOUNDARY") || type.contains("SENSITIVE")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private double numberOf(Object value, double fallback) {
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

    private String preferNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }
}
