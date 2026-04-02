package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.entity.PlanPhase;
import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.PlanNodeStatus;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.mapper.PlanNodeMapper;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.PhaseExecutionService;
import org.yilena.luna.executor.WorkflowExecutor;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.tools.PlanEventTools;
import org.yilena.luna.tools.PlanNodeTools;
import org.yilena.luna.constants.McpConstant;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 阶段执行服务实现
 *
 * 核心设计：
 * 1. 基于 DAG 拓扑排序将节点分为多个执行批次
 * 2. 同批次内使用虚拟线程并行执行，批次间串行
 * 3. 节点失败时根据是否为关键节点决定是否中断阶段
 * 4. 全流程通过 PlanEventTools 推送 SSE 事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhaseExecutionServiceImpl implements PhaseExecutionService {

    private static final int DEFAULT_MAX_RETRY = 1;

    // 单批次并行执行超时（秒）
    private static final int BATCH_TIMEOUT_SEC = 600;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );

    private final PlanNodeMapper planNodeMapper;
    private final PlanNodeTools planNodeTools;
    private final PlanEventTools planEventTools;
    private final AgentService agentService;
    private final McpService mcpService;
    private final WorkflowExecutor workflowExecutor;
    private final ToolExecutionGateway toolExecutionGateway;
    private final LunaStatusPublisher statusPublisher;
    private final ObjectMapper objectMapper;

    // =========================================================
    // 公共入口
    // =========================================================

    @Override
    public String executePhase(String planId, PlanPhase phase, String sessionId) {
        String phaseId = phase.getPhaseId();
        int phaseOrder = phase.getPhaseOrder() == null ? 0 : phase.getPhaseOrder();
        long phaseStart = System.currentTimeMillis();

        log.info("[Phase] 开始执行阶段, planId={}, phaseId={}, phaseOrder={}, name={}",
                planId, phaseId, phaseOrder, phase.getName());

        // 1. 加载阶段节点
        List<PlanNode> nodes = loadPhaseNodes(planId, phaseId);
        if (nodes.isEmpty()) {
            log.warn("[Phase] 阶段无节点, planId={}, phaseId={}", planId, phaseId);
            return buildPhaseResult(planId, phaseId, phaseOrder, 0, 0, 0,
                    System.currentTimeMillis() - phaseStart, "阶段下无节点");
        }

        log.info("[Phase] 加载节点完成, planId={}, phaseId={}, nodeCount={}", planId, phaseId, nodes.size());

        // 2. 拓扑排序，生成执行批次
        List<List<PlanNode>> batches;
        try {
            batches = resolveExecutionBatches(nodes);
        } catch (IllegalStateException e) {
            // 有环或其他拓扑异常
            log.error("[Phase] 拓扑排序失败, planId={}, phaseId={}, err={}", planId, phaseId, e.getMessage());
            return buildErrorResult("PHASE_TOPOLOGY_ERROR", "节点依赖图存在环路或非法依赖: " + e.getMessage());
        }

        log.info("[Phase] 拓扑排序完成, planId={}, phaseId={}, batchCount={}", planId, phaseId, batches.size());

        // 3. 逐批次执行
        int totalSuccess = 0;
        int totalFail = 0;
        int totalPendingApproval = 0;

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<PlanNode> batch = batches.get(batchIdx);

            log.info("[Phase] 执行批次 {}/{}, planId={}, phaseId={}, batchSize={}",
                    batchIdx + 1, batches.size(), planId, phaseId, batch.size());

            BatchResult result = executeBatch(planId, phaseId, phaseOrder, batch, sessionId, batchIdx + 1, batches.size());
            totalSuccess += result.successCount();
            totalFail += result.failCount();
            totalPendingApproval += result.pendingApprovalCount();

            if (result.interruptReason() == InterruptReason.FAILURE) {
                log.error("[Phase] 批次 {}/{} 存在失败节点，终止后续批次, planId={}, phaseId={}, failCount={}",
                        batchIdx + 1, batches.size(), planId, phaseId, result.failCount());
                break;
            }
            if (result.interruptReason() == InterruptReason.PENDING_APPROVAL) {
                log.warn("[Phase] 批次 {}/{} 存在待审批节点，阶段暂停并终止后续批次, planId={}, phaseId={}, pendingApprovalCount={}",
                        batchIdx + 1, batches.size(), planId, phaseId, result.pendingApprovalCount());
                break;
            }
        }

        long phaseCostMs = System.currentTimeMillis() - phaseStart;
        log.info("[Phase] 阶段执行完成, planId={}, phaseId={}, phaseOrder={}, success={}, fail={}, pendingApproval={}, costMs={}",
                planId, phaseId, phaseOrder, totalSuccess, totalFail, totalPendingApproval, phaseCostMs);

        if (totalPendingApproval > 0) {
            return buildErrorResult("PHASE_PENDING_APPROVAL", "阶段存在待审批节点，执行已暂停");
        }

        return buildPhaseResult(planId, phaseId, phaseOrder, totalSuccess, totalFail, totalPendingApproval, phaseCostMs, null);
    }

    // =========================================================
    // DAG 拓扑排序（Kahn 算法）
    // =========================================================

    @Override
    public List<List<PlanNode>> resolveExecutionBatches(List<PlanNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建 nodeId -> PlanNode 映射
        Map<String, PlanNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(PlanNode::getNodeId, n -> n));

        // 计算各节点入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (PlanNode node : nodes) {
            inDegree.put(node.getNodeId(), 0);
        }
        for (PlanNode node : nodes) {
            List<String> deps = node.getDependencies();
            if (deps == null || deps.isEmpty()) continue;
            for (String dep : deps) {
                if (inDegree.containsKey(dep)) {
                    // dep 是前置节点，当前节点入度+1
                    inDegree.merge(node.getNodeId(), 1, Integer::sum);
                } else {
                    log.warn("[Topo] 节点 {} 的依赖 {} 不在当前阶段节点列表中，忽略", node.getNodeId(), dep);
                }
            }
        }

        // BFS 分层
        List<List<PlanNode>> batches = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();

        // 入度为 0 的节点加入初始队列
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            // 本轮队列中所有节点组成一个并行批次
            List<PlanNode> batch = new ArrayList<>();
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String nodeId = queue.poll();
                if (nodeId != null && nodeMap.containsKey(nodeId)) {
                    batch.add(nodeMap.get(nodeId));
                    processed++;
                }
            }
            if (!batch.isEmpty()) {
                batches.add(batch);
            }

            // 减少后继节点入度
            for (PlanNode batchNode : batch) {
                for (PlanNode successor : nodes) {
                    List<String> deps = successor.getDependencies();
                    if (deps != null && deps.contains(batchNode.getNodeId())) {
                        int newDegree = inDegree.merge(successor.getNodeId(), -1, Integer::sum);
                        if (newDegree == 0) {
                            queue.offer(successor.getNodeId());
                        }
                    }
                }
            }
        }

        // 检测环路
        if (processed != nodes.size()) {
            throw new IllegalStateException(
                    String.format("节点依赖图存在环路，已处理节点数=%d，总节点数=%d", processed, nodes.size())
            );
        }

        log.info("[Topo] 拓扑排序完成，节点数={}, 批次数={}", nodes.size(), batches.size());
        return batches;
    }

    // =========================================================
    // 批次执行（虚拟线程并行）
    // =========================================================

    private BatchResult executeBatch(
            String planId, String phaseId, int phaseOrder,
            List<PlanNode> batch, String sessionId,
            int batchIdx, int totalBatches) {

        if (batch.size() == 1) {
            // 单节点直接同步执行，避免线程开销
            NodeResult nr = executeNode(planId, phaseId, phaseOrder, batch.get(0), sessionId);

            int successCount = nr.success() ? 1 : 0;
            int failCount = nr.success() ? 0 : (nr.approvalPending() ? 0 : 1);
            int pendingApprovalCount = nr.approvalPending() ? 1 : 0;

            InterruptReason reason = InterruptReason.NONE;
            if (pendingApprovalCount > 0) {
                reason = InterruptReason.PENDING_APPROVAL;
            } else if (failCount > 0) {
                reason = InterruptReason.FAILURE;
            }

            return new BatchResult(successCount, failCount, pendingApprovalCount, reason);
        }

        // 多节点并行执行（虚拟线程）
        log.info("[Batch] 启动并行批次, batchIdx={}/{}, nodeCount={}, planId={}, phaseId={}",
                batchIdx, totalBatches, batch.size(), planId, phaseId);

        try (ExecutorService vtp = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<NodeResult>> futures = batch.stream()
                    .map(node -> vtp.submit(() ->
                            executeNode(planId, phaseId, phaseOrder, node, sessionId)))
                    .toList();

            int successCount = 0;
            int failCount = 0;
            int pendingApprovalCount = 0;

            for (int i = 0; i < futures.size(); i++) {
                PlanNode node = batch.get(i);
                try {
                    NodeResult nr = futures.get(i).get(BATCH_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (nr.success()) {
                        successCount++;
                    } else if (nr.approvalPending()) {
                        pendingApprovalCount++;
                    } else {
                        failCount++;
                    }
                } catch (TimeoutException e) {
                    failCount++;
                    futures.get(i).cancel(true);
                    log.error("[Batch] 节点执行超时, nodeId={}, planId={}, phaseId={}", node.getNodeId(), planId, phaseId);
                    safeUpdateNodeStatus(planId, node.getNodeId(), PlanNodeStatus.FAILED,
                            (long) BATCH_TIMEOUT_SEC * 1000, "执行超时", node.getRetryCount() == null ? 0 : node.getRetryCount());
                } catch (ExecutionException e) {
                    NeedApprovalException needApprovalException = findNeedApprovalException(e);
                    if (needApprovalException != null || isNeedApprovalMessage(e)) {
                        pendingApprovalCount++;
                        String taskId = needApprovalException != null
                                ? extractApprovalTaskId(needApprovalException)
                                : extractApprovalTaskIdFromMessage(e.getMessage());

                        safeUpdateNodeStatus(planId, node.getNodeId(), PlanNodeStatus.APPROVAL_PENDING,
                                null, "等待审批, taskId=" + taskId, node.getRetryCount() == null ? 0 : node.getRetryCount());

                        emitNodeEvent(planId, phaseId, node.getNodeId(), "PLAN_NODE_APPROVAL_PENDING", "APPROVAL_PENDING", "INFO",
                                "节点需要审批，执行暂停", "NEED_APPROVAL",
                                node.getName() == null ? "" : node.getName(),
                                node.getNodeType() == null ? "" : node.getNodeType().getValue(),
                                node.getRetryCount() == null ? 0 : node.getRetryCount(),
                                node.getMaxRetry() == null ? DEFAULT_MAX_RETRY : node.getMaxRetry(),
                                0L, Map.of("taskId", taskId));

                        log.warn("[Batch] 节点进入审批等待, nodeId={}, planId={}, phaseId={}, taskId={}",
                                node.getNodeId(), planId, phaseId, taskId);
                    } else {
                        failCount++;
                        Throwable root = unwrapRootCause(e);
                        log.error("[Batch] 节点 Future 获取异常, nodeId={}, planId={}, phaseId={}, err={}",
                                node.getNodeId(), planId, phaseId, root != null ? root.getMessage() : e.getMessage(), e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failCount++;
                    log.error("[Batch] 等待节点结果被中断, nodeId={}, planId={}, phaseId={}",
                            node.getNodeId(), planId, phaseId);
                } catch (Exception e) {
                    NeedApprovalException needApprovalException = findNeedApprovalException(e);
                    if (needApprovalException != null || isNeedApprovalMessage(e)) {
                        pendingApprovalCount++;
                        String taskId = needApprovalException != null
                                ? extractApprovalTaskId(needApprovalException)
                                : extractApprovalTaskIdFromMessage(e.getMessage());

                        safeUpdateNodeStatus(planId, node.getNodeId(), PlanNodeStatus.APPROVAL_PENDING,
                                null, "等待审批, taskId=" + taskId, node.getRetryCount() == null ? 0 : node.getRetryCount());

                        emitNodeEvent(planId, phaseId, node.getNodeId(), "PLAN_NODE_APPROVAL_PENDING", "APPROVAL_PENDING", "INFO",
                                "节点需要审批，执行暂停", "NEED_APPROVAL",
                                node.getName() == null ? "" : node.getName(),
                                node.getNodeType() == null ? "" : node.getNodeType().getValue(),
                                node.getRetryCount() == null ? 0 : node.getRetryCount(),
                                node.getMaxRetry() == null ? DEFAULT_MAX_RETRY : node.getMaxRetry(),
                                0L, Map.of("taskId", taskId));

                        log.warn("[Batch] 节点进入审批等待（通用异常兜底识别）, nodeId={}, planId={}, phaseId={}, taskId={}",
                                node.getNodeId(), planId, phaseId, taskId);
                    } else {
                        failCount++;
                        log.error("[Batch] 节点 Future 获取异常, nodeId={}, planId={}, phaseId={}, err={}",
                                node.getNodeId(), planId, phaseId, e.getMessage(), e);
                    }
                }
            }

            InterruptReason reason = InterruptReason.NONE;
            if (pendingApprovalCount > 0) {
                reason = InterruptReason.PENDING_APPROVAL;
            } else if (failCount > 0) {
                reason = InterruptReason.FAILURE;
            }

            log.info("[Batch] 并行批次执行完成, batchIdx={}/{}, success={}, fail={}, pendingApproval={}, interruptReason={}, planId={}, phaseId={}",
                    batchIdx, totalBatches, successCount, failCount, pendingApprovalCount, reason, planId, phaseId);

            return new BatchResult(successCount, failCount, pendingApprovalCount, reason);
        }
    }

    // =========================================================
    // 单节点执行（含重试）
    // =========================================================

    private NodeResult executeNode(String planId, String phaseId, int phaseOrder, PlanNode node, String sessionId) {
        String nodeId = node.getNodeId();
        String nodeName = node.getName() == null ? "" : node.getName();
        String nodeType = node.getNodeType() == null ? "" : node.getNodeType().getValue();
        int retryCount = node.getRetryCount() == null ? 0 : node.getRetryCount();
        int maxRetry = node.getMaxRetry() == null ? DEFAULT_MAX_RETRY : node.getMaxRetry();
        long nodeStart = System.currentTimeMillis();

        log.info("[Node] 开始执行, planId={}, phaseId={}, phaseOrder={}, nodeId={}, name={}, type={}, retry={}/{}",
                planId, phaseId, phaseOrder, nodeId, nodeName, nodeType, retryCount, maxRetry);

        // 校验状态流转合法性
        if (!canTransitToRunning(node.getStatus())) {
            log.warn("[Node] 节点状态流转不合法，跳过执行, nodeId={}, status={}, planId={}, phaseId={}",
                    nodeId, node.getStatus(), planId, phaseId);
            emitNodeEvent(planId, phaseId, nodeId, "PLAN_NODE_FAILED", "FAILED", "INFO",
                    "节点状态流转不合法", "NODE_INVALID_TRANSITION", nodeName, nodeType,
                    retryCount, maxRetry, 0L, Map.of());
            return new NodeResult(false, nodeId, false);
        }

        // 更新节点为 RUNNING
        safeUpdateNodeStatus(planId, nodeId, PlanNodeStatus.RUNNING, null, null, retryCount);

        // 推送节点开始事件
        emitNodeEvent(planId, phaseId, nodeId, "PLAN_NODE_RUNNING", "RUNNING", "INFO",
                "节点执行中", "", nodeName, nodeType, retryCount, maxRetry, 0L, Map.of());

        String nodeGoal = buildNodeGoal(planId, phaseId, node);
        String agentResult;
        boolean success;

        try {
            // capability 元数据齐全时，优先走定向执行；否则回退至 agent 决策执行。
            String directedResult = executeByCapability(node, sessionId);
            if (directedResult != null) {
                agentResult = directedResult;
            } else {
                agentResult = agentService.processToolCalling(sessionId, nodeGoal);
            }
            success = !isErrorResult(agentResult);
        } catch (NeedApprovalException e) {
            String taskId = extractApprovalTaskId(e);
            long costMs = System.currentTimeMillis() - nodeStart;

            safeUpdateNodeStatus(planId, nodeId, PlanNodeStatus.APPROVAL_PENDING, costMs,
                    "等待审批, taskId=" + taskId, retryCount);

            emitNodeEvent(planId, phaseId, nodeId, "PLAN_NODE_APPROVAL_PENDING", "APPROVAL_PENDING", "INFO",
                    "节点需要审批，执行暂停", "NEED_APPROVAL", nodeName, nodeType,
                    retryCount, maxRetry, costMs, Map.of("taskId", taskId));

            log.warn("[Node] 节点触发审批，进入等待状态, nodeId={}, planId={}, phaseId={}, taskId={}",
                    nodeId, planId, phaseId, taskId);

            return new NodeResult(false, nodeId, true);
        }

        if (!success) {
            // 首次失败，尝试重试
            for (int r = retryCount + 1; r <= maxRetry; r++) {
                String errMsg = extractErrorMessage(agentResult);
                String errCode = extractErrorCode(agentResult);
                log.warn("[Node] 节点首次失败，准备第 {} 次重试, nodeId={}, planId={}, phaseId={}, errorCode={}, failReason={}",
                        r, nodeId, planId, phaseId, errCode, errMsg);

                safeUpdateNodeStatus(planId, nodeId, PlanNodeStatus.RUNNING, null, null, r);

                try {
                    String directedResult = executeByCapability(node, sessionId);
                    if (directedResult != null) {
                        agentResult = directedResult;
                    } else {
                        agentResult = agentService.processToolCalling(sessionId, nodeGoal);
                    }
                } catch (NeedApprovalException e) {
                    String taskId = extractApprovalTaskId(e);
                    long costMs = System.currentTimeMillis() - nodeStart;

                    safeUpdateNodeStatus(planId, nodeId, PlanNodeStatus.APPROVAL_PENDING, costMs,
                            "等待审批, taskId=" + taskId, r);

                    emitNodeEvent(planId, phaseId, nodeId, "PLAN_NODE_APPROVAL_PENDING", "APPROVAL_PENDING", "INFO",
                            "节点需要审批，执行暂停", "NEED_APPROVAL", nodeName, nodeType,
                            r, maxRetry, costMs, Map.of("taskId", taskId));

                    log.warn("[Node] 节点重试过程中触发审批，进入等待状态, nodeId={}, planId={}, phaseId={}, taskId={}, retry={}",
                            nodeId, planId, phaseId, taskId, r);

                    return new NodeResult(false, nodeId, true);
                }

                success = !isErrorResult(agentResult);

                if (success) {
                    retryCount = r;
                    log.info("[Node] 节点第 {} 次重试成功, nodeId={}, planId={}, phaseId={}", r, nodeId, planId, phaseId);
                    break;
                }
            }
        }

        long costMs = System.currentTimeMillis() - nodeStart;

        if (success) {
            // 落库输出
            Map<String, Object> output = buildOutput(node, nodeGoal, agentResult);
            Map<String, Object> outputForNext = buildOutputForNext(nodeId, agentResult);
            safeAppendNodeOutput(planId, nodeId, output, outputForNext);
            safeUpdateNodeStatus(planId, nodeId, PlanNodeStatus.SUCCESS, costMs, null, retryCount);

            log.info("[Node] 节点执行成功, planId={}, phaseId={}, phaseOrder={}, nodeId={}, name={}, type={}, retry={}/{}, costMs={}",
                    planId, phaseId, phaseOrder, nodeId, nodeName, nodeType, retryCount, maxRetry, costMs);

            emitNodeEvent(planId, phaseId, nodeId, "PLAN_NODE_SUCCESS", "SUCCESS", "INFO",
                    "节点执行成功", "", nodeName, nodeType, retryCount, maxRetry, costMs, outputForNext);
        } else {
            String failReason = extractErrorMessage(agentResult);
            String errorCode = extractErrorCode(agentResult);
            safeUpdateNodeStatus(planId, nodeId, PlanNodeStatus.FAILED, costMs, failReason, maxRetry);

            log.error("[Node] 节点执行失败, planId={}, phaseId={}, phaseOrder={}, nodeId={}, name={}, type={}, retry={}/{}, costMs={}, errorCode={}, failReason={}",
                    planId, phaseId, phaseOrder, nodeId, nodeName, nodeType, maxRetry, maxRetry, costMs, errorCode, failReason);

            emitNodeEvent(planId, phaseId, nodeId, "PLAN_NODE_FAILED", "FAILED", "WARN",
                    failReason, errorCode, nodeName, nodeType, maxRetry, maxRetry, costMs, Map.of());
        }

        return new NodeResult(success, nodeId, false);
    }

    // =========================================================
    // 辅助方法
    // =========================================================

    /**
     * 加载阶段下所有节点，按 nodeId 排序
     */
    private List<PlanNode> loadPhaseNodes(String planId, String phaseId) {
        return planNodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanNode>()
                        .eq(PlanNode::getPlanId, planId)
                        .eq(PlanNode::getPhaseId, phaseId)
                        .orderByAsc(PlanNode::getCreatedAt)
        );
    }

    /**
     * 判断节点是否可以流转到 RUNNING 状态
     */
    private boolean canTransitToRunning(PlanNodeStatus status) {
        if (status == null) return true;
        return status == PlanNodeStatus.PENDING
                || status == PlanNodeStatus.BLOCKED
                || status == PlanNodeStatus.APPROVAL_PENDING;
    }

    /**
     * 构建节点目标描述，传给 AgentService
     */
    private String buildNodeGoal(String planId, String phaseId, PlanNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("计划ID=").append(planId)
                .append("；阶段ID=").append(phaseId)
                .append("；节点ID=").append(node.getNodeId())
                .append("；节点名称=").append(node.getName() == null ? "" : node.getName())
                .append("；节点类型=").append(node.getNodeType() == null ? "" : node.getNodeType().getValue());
        if (node.getCapabilityType() != null && !node.getCapabilityType().isBlank()) {
            sb.append("；能力类型=").append(node.getCapabilityType());
        }
        if (node.getCapabilityName() != null && !node.getCapabilityName().isBlank()) {
            sb.append("；能力名称=").append(node.getCapabilityName());
        }
        if (node.getServerCode() != null && !node.getServerCode().isBlank()) {
            sb.append("；服务编码=").append(node.getServerCode());
        }
        if (node.getInputJson() != null && !node.getInputJson().isEmpty()) {
            sb.append("；输入=").append(toJsonQuiet(node.getInputJson()));
        }
        if (node.getResolvedInputJson() != null && !node.getResolvedInputJson().isEmpty()) {
            sb.append("；解析后输入=").append(toJsonQuiet(node.getResolvedInputJson()));
        }
        if (node.getResourceHint() != null && !node.getResourceHint().isEmpty()) {
            sb.append("；资源提示=").append(toJsonQuiet(node.getResourceHint()));
        }
        if (node.getExpectedOutputSchema() != null && !node.getExpectedOutputSchema().isEmpty()) {
            sb.append("；期望输出Schema=").append(toJsonQuiet(node.getExpectedOutputSchema()));
        }
        return sb.toString();
    }

    /**
     * 构建节点输出 Map
     */
    private Map<String, Object> buildOutput(PlanNode node, String nodeGoal, String agentResult) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("nodeName", node.getName());
        output.put("nodeGoal", nodeGoal);
        output.put("agentResult", safeParse(agentResult));
        output.put("result", "ok");
        return output;
    }

    /**
     * 构建传递给下游节点的关键数据
     */
    private Map<String, Object> buildOutputForNext(String nodeId, String agentResult) {
        Map<String, Object> outputForNext = new LinkedHashMap<>();
        outputForNext.put("nodeId", nodeId);
        outputForNext.put("result", "ok");
        outputForNext.put("agentResult", safeParse(agentResult));
        return outputForNext;
    }

    private String executeByCapability(PlanNode node, String sessionId) {
        String capabilityName = text(node.getCapabilityName());
        if (capabilityName.isBlank()) {
            return null;
        }
        String capabilityType = normalizeCapabilityType(node.getCapabilityType(), node.getNodeType());
        String serverCode = text(node.getServerCode());
        if (serverCode.isBlank()) {
            serverCode = McpConstant.LOCAL_SERVER_CODE;
        }
        String argsJson = toJsonQuiet(resolveNodeInput(node));

        return switch (capabilityType) {
            case "TOOL" -> executeToolCapability(serverCode, capabilityName, sessionId, argsJson);
            case "PROMPT" -> toJsonQuiet(Map.of(
                    "status", "success",
                    "data", mcpService.getPrompt(serverCode, capabilityName, argsJson)
            ));
            case "RESOURCE" -> toJsonQuiet(Map.of(
                    "status", "success",
                    "data", mcpService.readResource(serverCode, capabilityName)
            ));
            case "WORKFLOW" -> executeWorkflowCapability(serverCode, capabilityName, argsJson);
            default -> null;
        };
    }

    private String executeToolCapability(String serverCode, String capabilityName, String sessionId, String argsJson) {
        Resource resource = resolveCapabilityResource(serverCode, capabilityName, ResourceType.TOOL);
        if (resource == null) {
            return toJsonQuiet(mcpService.callTool(serverCode, capabilityName, argsJson));
        }
        ExecutionResult result = toolExecutionGateway.executeTool(
                sessionId == null || sessionId.isBlank() ? "phase-executor" : sessionId,
                resource,
                argsJson
        );
        if (result.getRawResult() != null && !result.getRawResult().isBlank()) {
            return result.getRawResult();
        }
        return toJsonQuiet(Map.of(
                "status", result.getStatus() == null ? "success" : result.getStatus(),
                "message", result.getMessage() == null ? "" : result.getMessage(),
                "data", result.getData() == null ? Map.of() : result.getData()
        ));
    }

    private String executeWorkflowCapability(String serverCode, String capabilityName, String argsJson) {
        Resource resource = resolveCapabilityResource(serverCode, capabilityName, ResourceType.WORKFLOW);
        if (resource == null) {
            return buildErrorResult("WORKFLOW_NOT_FOUND", "workflow capability not found: " + capabilityName);
        }
        return workflowExecutor.execute(resource, argsJson);
    }

    private Resource resolveCapabilityResource(String serverCode, String capabilityName, ResourceType expectedType) {
        List<Resource> all = mcpService.listAll();
        String targetServer = text(serverCode);
        String targetName = text(capabilityName);
        return all.stream()
                .filter(r -> expectedType.equals(r.getType()))
                .filter(r -> targetServer.isBlank() || targetServer.equalsIgnoreCase(text(r.getServerCode())))
                .filter(r -> targetName.equalsIgnoreCase(text(r.getName())) || targetName.equalsIgnoreCase(text(r.getResourceUri())))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> resolveNodeInput(PlanNode node) {
        if (node.getResolvedInputJson() != null && !node.getResolvedInputJson().isEmpty()) {
            return node.getResolvedInputJson();
        }
        if (node.getInputJson() != null && !node.getInputJson().isEmpty()) {
            return node.getInputJson();
        }
        return Map.of();
    }

    private String normalizeCapabilityType(String rawType, org.yilena.luna.enums.PlanNodeType nodeType) {
        if (rawType != null && !rawType.isBlank()) {
            String normalized = rawType.trim().toUpperCase(Locale.ROOT);
            return normalized;
        }
        if (nodeType == null) {
            return "TOOL";
        }
        return switch (nodeType) {
            case PROMPT -> "PROMPT";
            case RESOURCE -> "RESOURCE";
            case WORKFLOW -> "WORKFLOW";
            default -> "TOOL";
        };
    }

    /**
     * 安全地更新节点状态，异常不抛出，只记录日志
     */
    private void safeUpdateNodeStatus(String planId, String nodeId, PlanNodeStatus status,
                                      Long costMs, String failReason, int retryCount) {
        try {
            planNodeTools.updateNodeStatus(
                    planId, nodeId, status.getValue(), costMs, failReason, retryCount
            );
        } catch (Exception e) {
            log.error("[Node] 更新节点状态失败（不中断主流程）, nodeId={}, status={}, err={}",
                    nodeId, status, e.getMessage());
        }
    }

    /**
     * 安全地写入节点输出，异常不抛出，只记录日志
     */
    private void safeAppendNodeOutput(String planId, String nodeId,
                                      Map<String, Object> output, Map<String, Object> outputForNext) {
        try {
            planNodeTools.appendNodeOutput(
                    planId, nodeId,
                    objectMapper.writeValueAsString(output),
                    objectMapper.writeValueAsString(outputForNext)
            );
        } catch (Exception e) {
            log.error("[Node] 写入节点输出失败（不中断主流程）, nodeId={}, err={}", nodeId, e.getMessage());
        }
    }

    /**
     * 推送节点级 SSE 事件并落库
     */
    private void emitNodeEvent(String planId, String phaseId, String nodeId,
                               String eventType, String status, String level,
                               String message, String errorCode,
                               String nodeName, String nodeType,
                               int retryCount, int maxRetry, long costMs,
                               Map<String, Object> outputForNext) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", eventType);
            payload.put("planId", planId);
            payload.put("phaseId", phaseId);
            payload.put("nodeId", nodeId);
            payload.put("status", status);
            payload.put("message", message);
            payload.put("nodeName", nodeName);
            payload.put("nodeType", nodeType);
            payload.put("errorCode", errorCode == null ? "" : errorCode);
            payload.put("retryCount", retryCount);
            payload.put("maxRetry", maxRetry);
            payload.put("costMs", costMs);
            payload.put("outputForNext", outputForNext);
            payload.put("timestamp", System.currentTimeMillis());

            planEventTools.emitPlanEvent(
                    "default", planId,
                    phaseId, nodeId, level, eventType,
                    objectMapper.writeValueAsString(payload),
                    java.util.UUID.randomUUID().toString()
            );
        } catch (Exception e) {
            log.warn("[Node] 推送节点事件失败（不中断主流程）, nodeId={}, eventType={}, err={}",
                    nodeId, eventType, e.getMessage());
        }
    }

    /**
     * 构建阶段执行结果 JSON
     */
    private String buildPhaseResult(String planId, String phaseId, int phaseOrder,
                                    int successCount, int failCount, int pendingApprovalCount, long costMs, String note) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", (failCount > 0 || pendingApprovalCount > 0) ? "error" : "success");
            out.put("planId", planId);
            out.put("phaseId", phaseId);
            out.put("phaseOrder", phaseOrder);
            out.put("successCount", successCount);
            out.put("failCount", failCount);
            out.put("pendingApprovalCount", pendingApprovalCount);
            out.put("costMs", costMs);

            String msg;
            if (note != null) {
                msg = note;
            } else if (pendingApprovalCount > 0) {
                msg = "阶段存在待审批节点，执行已暂停";
            } else if (failCount > 0) {
                msg = "阶段存在失败节点";
            } else {
                msg = "阶段执行成功";
            }
            out.put("message", msg);

            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"结果序列化失败\"}";
        }
    }

    /**
     * 构建错误结果 JSON
     */
    private String buildErrorResult(String errorCode, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", errorCode,
                    "message", message
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"errorCode\":\"" + errorCode + "\"}";
        }
    }

    private boolean isErrorResult(String result) {
        if (result == null || result.isBlank()) return true;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(result);
            if (node.has("status")) {
                String s = node.get("status").asText("");
                return "error".equalsIgnoreCase(s) || "failed".equalsIgnoreCase(s);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractErrorMessage(String result) {
        if (result == null || result.isBlank()) return "unknown error";
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(result);

            String msg = firstNonBlank(
                    node.path("message").asText(""),
                    node.path("error").asText(""),
                    node.path("reason").asText(""),
                    node.path("detail").asText("")
            );
            if (msg != null) {
                return truncate(msg, 300);
            }

            String status = node.path("status").asText("");
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                return truncate(node.toString(), 300);
            }

            return "unknown error";
        } catch (Exception e) {
            // 非 JSON 文本，直接作为错误信息回传，避免丢失上下文
            return truncate(result, 300);
        }
    }

    private String extractErrorCode(String result) {
        if (result == null || result.isBlank()) return "UNKNOWN_ERROR";
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(result);

            String code = firstNonBlank(
                    node.path("errorCode").asText(""),
                    node.path("code").asText("")
            );
            if (code != null) {
                return truncate(code, 80);
            }

            String status = node.path("status").asText("");
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                return "UNKNOWN_ERROR";
            }

            return "UNKNOWN_ERROR";
        } catch (Exception e) {
            return "UNKNOWN_ERROR";
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, Math.max(0, maxLen)) + "...";
    }

    private Object safeParse(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return text;
        }
    }

    private String toJsonQuiet(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Throwable unwrapRootCause(Throwable t) {
        Throwable cur = t;
        while (cur != null && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private NeedApprovalException findNeedApprovalException(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            if (cur instanceof NeedApprovalException nae) {
                return nae;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private boolean isNeedApprovalMessage(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("needapprovalexception") || lower.contains("操作需要審批") || lower.contains("操作需要审批")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String extractApprovalTaskId(NeedApprovalException e) {
        try {
            if (e != null && e.getApprovalTask() != null && e.getApprovalTask().getTaskId() != null) {
                return e.getApprovalTask().getTaskId();
            }
        } catch (Exception ignore) {
        }
        return extractApprovalTaskIdFromMessage(e != null ? e.getMessage() : null);
    }

    private String extractApprovalTaskIdFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        Matcher m = UUID_PATTERN.matcher(message);
        if (m.find()) {
            return m.group();
        }
        return "unknown";
    }

    // =========================================================
    // 内部值对象
    // =========================================================

    private enum InterruptReason {
        NONE,
        FAILURE,
        PENDING_APPROVAL
    }

    /**
     * 单节点执行结果
     */
    private record NodeResult(boolean success, String nodeId, boolean approvalPending) {}

    /**
     * 批次执行结果
     */
    private record BatchResult(int successCount, int failCount, int pendingApprovalCount, InterruptReason interruptReason) {}
}
