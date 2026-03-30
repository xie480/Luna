package org.yilena.luna.memory.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.memory.SocialReasonerService;

import java.util.HashMap;
import java.util.Map;

@Service
public class DefaultSocialReasonerService implements SocialReasonerService {

    @Override
    public Map<String, Object> buildRelationalDraft(String sessionId,
                                                    String userInput,
                                                    RelationalRuntimeState relationalState,
                                                    Map<String, Object> relationalContext) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("session_id", sessionId);
        draft.put("relational_state", relationalState.name());
        draft.put("recommended_tone", decideTone(relationalState));
        draft.put("empathy_first", shouldEmpathyFirst(relationalState));
        draft.put("question_intensity", decideQuestionIntensity(relationalState));
        draft.put("response_style", decideResponseStyle(relationalState));
        draft.put("closing_style", decideClosingStyle(relationalState));
        draft.put("source", "social_reasoner");
        return draft;
    }

    private String decideTone(RelationalRuntimeState state) {
        return switch (state) {
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "soft_and_calm";
            case REPAIRING -> "careful_and_humble";
            case CELEBRATING -> "warm_and_positive";
            case COMPANION_MODE, LIGHT_CHAT, DEEP_TALK -> "natural_and_companion";
            default -> "clear_and_friendly";
        };
    }

    private boolean shouldEmpathyFirst(RelationalRuntimeState state) {
        return state == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || state == RelationalRuntimeState.FRAGILE_MOMENT
                || state == RelationalRuntimeState.REPAIRING;
    }

    private String decideQuestionIntensity(RelationalRuntimeState state) {
        return switch (state) {
            case FRAGILE_MOMENT, EMOTIONAL_SUPPORT -> "low";
            case REPAIRING -> "medium_low";
            case LIGHT_CHAT, COMPANION_MODE -> "medium";
            default -> "medium_high";
        };
    }

    private String decideResponseStyle(RelationalRuntimeState state) {
        return switch (state) {
            case EMOTIONAL_SUPPORT -> "listen_then_small_steps";
            case FRAGILE_MOMENT -> "stabilize_then_minimum_plan";
            case REPAIRING -> "acknowledge_then_realign";
            case CELEBRATING -> "affirm_then_next_goal";
            default -> "task_with_warmth";
        };
    }

    private String decideClosingStyle(RelationalRuntimeState state) {
        return switch (state) {
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "gentle_check_in";
            case REPAIRING -> "confirm_alignment";
            case CELEBRATING -> "positive_anchor";
            default -> "actionable_next_step";
        };
    }
}
