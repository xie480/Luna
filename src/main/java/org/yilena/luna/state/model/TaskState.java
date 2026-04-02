package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class TaskState {
    String taskId;
    String sessionId;
    String objective;
    String currentStage;
    String currentNode;
    Map<String, Object> confirmedSlots;
    List<String> pendingQuestions;
    List<String> finishedSteps;
    List<String> failedSteps;
    Integer retryCount;
    String nextActionHint;
}

