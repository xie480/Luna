package org.yilena.luna.service;

import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.model.BlueprintOrchestrationResult;
import org.yilena.luna.service.model.MainModelExecutionRequest;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundStateWriteRequest;
import org.yilena.luna.service.model.SummaryOrchestrationResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.service.model.ToolDecisionNodeResult;

import java.util.List;
import java.util.Map;

/**
 * 任务编排服务接口，负责把用户输入、恢复事件、节点工作集、工具决策、主模型执行和状态写回
 * 拆分为可复用的编排阶段，是任务主链路的核心协调入口。
 */
public interface TaskOrchestratorService {

    TaskOrchestrationResult orchestrateUserInput(String sessionId, String userInput);

    TaskOrchestrationResult orchestrateSystemRecovery(String sessionId,
                                                      String userInput,
                                                      String eventType,
                                                      Map<String, Object> eventPayload,
                                                      String recoveryEvent,
                                                      String interruptReason);

    NodeWorksetResult orchestrateNodeWorkset(String sessionId,
                                             String userInput,
                                             OrchestrationDecision decision,
                                             StructuredContextPackage contextPackage,
                                             InputReconstructionResult reconstructionResult);

    ToolDecisionNodeResult orchestrateToolDecisionNode(String sessionId,
                                                       String userInput,
                                                       OrchestrationDecision decision,
                                                       StructuredContextPackage contextPackage,
                                                       InputReconstructionResult reconstructionResult,
                                                       NodeWorksetResult nodeWorksetResult);

    BlueprintOrchestrationResult orchestrateBlueprintInput(String sessionId, String userGoal);

    SummaryOrchestrationResult orchestrateSummary(String sessionId,
                                                  String userInput,
                                                  String assistantReply,
                                                  StructuredContextPackage contextPackage,
                                                  List<EvidenceBlock> activeEvidenceBlocks,
                                                  List<String> activeMcpResourceHints,
                                                  ToolSemanticResult latestToolSemanticResult,
                                                  boolean replaceHistory,
                                                  String triggerSource);

    MainModelOrchestrationResult orchestrateMainModel(MainModelExecutionRequest request);

    void writeRoundState(RoundStateWriteRequest request);
}
