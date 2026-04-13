import { ref } from "vue";
import { planGraph } from "../api/index.js";

function nowTs() {
  return Date.now();
}

function toObj(value) {
  return value && typeof value === "object" ? value : {};
}

function normalizeStatus(status) {
  const text = String(status || "").toUpperCase();
  if (!text) return "PENDING";
  return text;
}

function normalizePlanStatusTransition(prevStatus, nextStatus, locked) {
  const prev = normalizeStatus(prevStatus || "PENDING");
  const next = normalizeStatus(nextStatus || prev);

  if (locked) return prev;

  const terminal = ["SUCCESS", "FAILED", "PARTIAL", "CANCELLED"];
  if (terminal.includes(prev) && next === "RUNNING") return prev;

  return next;
}

function normalizeNodeStatusTransition(prevStatus, nextStatus) {
  const prev = normalizeStatus(prevStatus);
  const next = normalizeStatus(nextStatus);

  if (prev === "SUCCESS" && next === "RUNNING") return prev;
  if (prev === "FAILED" && next === "RUNNING") return prev;

  return next;
}

function normalizeCount(value) {
  const count = Number(value);
  return Number.isFinite(count) ? count : 0;
}

function buildNodeStatsFromNodes(nodes) {
  const stats = {};

  Object.values(nodes || {}).forEach((node) => {
    const status = normalizeStatus(node?.status || "PENDING");
    stats[status] = (stats[status] || 0) + 1;
  });

  return stats;
}

function normalizeNodeStats(nodeStats, nodes = {}) {
  if (!nodeStats || typeof nodeStats !== "object") {
    return buildNodeStatsFromNodes(nodes);
  }

  const stats = {};
  Object.entries(nodeStats).forEach(([status, count]) => {
    stats[normalizeStatus(status)] = normalizeCount(count);
  });

  return stats;
}

function pickFinalStatus(payload) {
  const candidates = [
    payload?.finalStatus,
    payload?.report?.finalStatus,
    payload?.reportResult?.finalStatus,
  ];

  for (const candidate of candidates) {
    if (candidate !== undefined && candidate !== null && String(candidate).trim()) {
      return normalizeStatus(candidate);
    }
  }

  const rawStatus = String(payload?.status || "").trim();
  if (!rawStatus) return "";

  const normalized = normalizeStatus(rawStatus);
  return ["SUCCESS", "FAILED", "PARTIAL", "CANCELLED"].includes(normalized) ? normalized : "";
}

function pickReportInfo(payload) {
  const candidates = [payload, payload?.report, payload?.reportResult];

  for (const item of candidates) {
    if (!item || typeof item !== "object") continue;
    const reportPath = item.reportPath || "";
    const reportUrl = item.reportUrl || "";
    if (reportPath || reportUrl) {
      return { reportPath, reportUrl };
    }
  }

  return { reportPath: "", reportUrl: "" };
}

function normalizeSnapshot(snapshot, planIdHint = "") {
  const data = snapshot?.data ?? snapshot ?? {};
  const planId = data.planId || planIdHint || "";

  const phases = {};
  const nodes = {};
  const rawEdges = Array.isArray(data.edges) ? data.edges : [];

  const phaseList = Array.isArray(data.phases) ? data.phases : [];
  const nodeList = Array.isArray(data.nodes) ? data.nodes : [];

  phaseList.forEach((phase, index) => {
    const phaseId = phase.phaseId || phase.id || `phase-${index + 1}`;
    phases[phaseId] = {
      phaseId,
      phaseOrder: phase.phaseOrder ?? phase.order ?? index,
      name: phase.name || phaseId,
      status: normalizeStatus(phase.status || "PENDING"),
      successCount: phase.successCount ?? 0,
      failCount: phase.failCount ?? 0,
      nodeIds: Array.isArray(phase.nodeIds) ? phase.nodeIds.slice() : [],
      lastEventTs: phase.timestamp || phase.updatedAt || 0,
    };
  });

  nodeList.forEach((node, index) => {
    const nodeId = node.nodeId || node.id || `node-${index + 1}`;
    const phaseId = node.phaseId || node.phase || "unknown-phase";

    nodes[nodeId] = {
      nodeId,
      phaseId,
      nodeName: node.nodeName || node.name || nodeId,
      skillName: node.skillName || node.nodeName || node.name || "unknown-node",
      nodeType: node.nodeType || "",
      status: normalizeStatus(node.status || "PENDING"),
      failReason: node.failReason || "",
      errorCode: node.errorCode || "",
      retryCount: node.retryCount ?? 0,
      costMs: node.costMs ?? 0,
      outputForNext: node.outputForNext ?? {},
      message: node.message || "",
      timestamp: node.timestamp || node.updatedAt || 0,
      lastEventTs: node.timestamp || node.updatedAt || 0,
    };

    if (!phases[phaseId]) {
      phases[phaseId] = {
        phaseId,
        phaseOrder: Number.MAX_SAFE_INTEGER,
        name: phaseId,
        status: "PENDING",
        successCount: 0,
        failCount: 0,
        nodeIds: [],
        lastEventTs: 0,
      };
    }

    if (!phases[phaseId].nodeIds.includes(nodeId)) {
      phases[phaseId].nodeIds.push(nodeId);
    }
  });

  const edges = rawEdges.map((edge, index) => ({
    fromNodeId: edge.fromNodeId || edge.from || edge.source || `unknown-from-${index}`,
    toNodeId: edge.toNodeId || edge.to || edge.target || `unknown-to-${index}`,
    conditionExpr: edge.conditionExpr || "",
  }));

  const phaseOrder = Object.values(phases)
    .sort((left, right) => (left.phaseOrder ?? 0) - (right.phaseOrder ?? 0))
    .map((phase) => phase.phaseId);

  const reportInfo = pickReportInfo(data);

  return {
    planId,
    phases,
    phaseOrder,
    nodes,
    edges,
    nodeStats: normalizeNodeStats(data.nodeStats, nodes),
    planVersion: data.planVersion || data.version || "",
    finalStatus: pickFinalStatus(data),
    createdAt: data.createdAt || data.startTime || 0,
    updatedAt: data.updatedAt || data.timestamp || 0,
    lastSyncAt: nowTs(),
    status: normalizeStatus(data.status || "RUNNING"),
    message: data.message || "",
    reportPath: reportInfo.reportPath,
    reportUrl: reportInfo.reportUrl,
  };
}

export function usePlanGraphStore() {
  const graph = ref({
    planId: "",
    phases: {},
    phaseOrder: [],
    nodes: {},
    edges: [],
    nodeStats: {},
    planVersion: "",
    finalStatus: "",
    createdAt: 0,
    updatedAt: 0,
    lastSyncAt: 0,
  });

  const asyncEvents = ref([]);
  const report = ref({ ready: false, reportPath: "", reportUrl: "", finalStatus: "" });
  const runtimeStatus = ref("IDLE");
  const runtimeMessage = ref("");
  const errors = ref([]);
  const locked = ref(false);

  function reset(planId = "") {
    graph.value = {
      planId,
      phases: {},
      phaseOrder: [],
      nodes: {},
      edges: [],
      nodeStats: {},
      planVersion: "",
      finalStatus: "",
      createdAt: 0,
      updatedAt: 0,
      lastSyncAt: nowTs(),
    };
    asyncEvents.value = [];
    report.value = { ready: false, reportPath: "", reportUrl: "", finalStatus: "" };
    runtimeStatus.value = planId ? "RUNNING" : "IDLE";
    runtimeMessage.value = "";
    errors.value = [];
    locked.value = false;
  }

  function lockPlanFinalState(planId = "") {
    if (planId && graph.value.planId && graph.value.planId !== planId) return;
    locked.value = true;
  }

  function refreshNodeStats() {
    graph.value.nodeStats = buildNodeStatsFromNodes(graph.value.nodes);
  }

  function updatePlanMeta(payload) {
    const meta = toObj(payload);
    if (!meta || Object.keys(meta).length === 0) return;

    const createdAt = meta.createdAt || meta.startTime || 0;
    const updatedAt = meta.updatedAt || meta.timestamp || 0;
    const planVersion = meta.planVersion || meta.version || "";
    const finalStatus = pickFinalStatus(meta);
    const nextReport = pickReportInfo(meta);

    if (createdAt) graph.value.createdAt = createdAt;
    if (updatedAt) graph.value.updatedAt = updatedAt;
    if (planVersion) graph.value.planVersion = planVersion;
    if (meta.nodeStats && typeof meta.nodeStats === "object") {
      graph.value.nodeStats = normalizeNodeStats(meta.nodeStats, graph.value.nodes);
    }
    if (finalStatus) {
      graph.value.finalStatus = finalStatus;
    }
    if (nextReport.reportPath || nextReport.reportUrl || finalStatus) {
      report.value = {
        ready: report.value.ready || !!nextReport.reportPath || !!nextReport.reportUrl,
        reportPath: nextReport.reportPath || report.value.reportPath || "",
        reportUrl: nextReport.reportUrl || report.value.reportUrl || "",
        finalStatus: finalStatus || report.value.finalStatus || "",
      };
    }
  }

  function replaceFromSnapshot(snapshot, planIdHint = "") {
    const next = normalizeSnapshot(snapshot, planIdHint);
    graph.value = {
      planId: next.planId,
      phases: next.phases,
      phaseOrder: next.phaseOrder,
      nodes: next.nodes,
      edges: next.edges,
      nodeStats: next.nodeStats,
      planVersion: next.planVersion,
      finalStatus: next.finalStatus,
      createdAt: next.createdAt,
      updatedAt: next.updatedAt || next.lastSyncAt,
      lastSyncAt: next.lastSyncAt,
    };

    runtimeStatus.value = normalizePlanStatusTransition(
      runtimeStatus.value,
      next.finalStatus || next.status,
      locked.value,
    );
    runtimeMessage.value = next.message || runtimeMessage.value || "";

    if (next.reportPath || next.reportUrl || next.finalStatus) {
      report.value = {
        ready: report.value.ready || !!next.reportPath || !!next.reportUrl,
        reportPath: next.reportPath || report.value.reportPath || "",
        reportUrl: next.reportUrl || report.value.reportUrl || "",
        finalStatus: next.finalStatus || report.value.finalStatus || "",
      };
    }

    if (next.finalStatus) {
      lockPlanFinalState(next.planId);
    }
  }

  async function syncGraphSnapshot(planId) {
    if (!planId) return;
    const snapshot = await planGraph(planId);
    replaceFromSnapshot(snapshot, planId);
  }

  function ensurePhase(phaseId, phaseOrder = Number.MAX_SAFE_INTEGER) {
    const currentGraph = graph.value;
    if (!currentGraph.phases[phaseId]) {
      currentGraph.phases[phaseId] = {
        phaseId,
        phaseOrder,
        name: phaseId,
        status: "PENDING",
        successCount: 0,
        failCount: 0,
        nodeIds: [],
        lastEventTs: 0,
      };
      currentGraph.phaseOrder = Object.values(currentGraph.phases)
        .sort((left, right) => (left.phaseOrder ?? 0) - (right.phaseOrder ?? 0))
        .map((phase) => phase.phaseId);
    }
  }

  function upsertNode(payload, statusHint = "") {
    const currentGraph = graph.value;
    const timestamp = payload.timestamp || nowTs();
    const nodeId = payload.nodeId || `node-${timestamp}`;
    const phaseId = payload.phaseId || "unknown-phase";
    ensurePhase(phaseId, payload.phaseOrder ?? Number.MAX_SAFE_INTEGER);

    const previous = currentGraph.nodes[nodeId] || {
      nodeId,
      phaseId,
      nodeName: nodeId,
      skillName: "unknown-node",
      nodeType: "",
      status: "PENDING",
      failReason: "",
      errorCode: "",
      retryCount: 0,
      costMs: 0,
      outputForNext: {},
      message: "",
      timestamp: 0,
      lastEventTs: 0,
    };

    if (previous.lastEventTs && timestamp < previous.lastEventTs) {
      return;
    }

    const status = normalizeNodeStatusTransition(previous.status, statusHint || payload.status || previous.status);

    currentGraph.nodes[nodeId] = {
      ...previous,
      phaseId,
      nodeName: payload.nodeName || previous.nodeName || nodeId,
      skillName: payload.skillName || payload.nodeName || previous.skillName || "unknown-node",
      nodeType: payload.nodeType || previous.nodeType || "",
      status,
      failReason: payload.failReason ?? previous.failReason ?? "",
      errorCode: payload.errorCode ?? previous.errorCode ?? "",
      retryCount: payload.retryCount ?? previous.retryCount ?? 0,
      costMs: payload.costMs ?? previous.costMs ?? 0,
      outputForNext: payload.outputForNext ?? previous.outputForNext ?? {},
      message: payload.message ?? previous.message ?? "",
      timestamp,
      lastEventTs: timestamp,
    };

    if (!currentGraph.phases[phaseId].nodeIds.includes(nodeId)) {
      currentGraph.phases[phaseId].nodeIds.push(nodeId);
    }
  }

  function applyEvent(eventPayload) {
    const payload = toObj(eventPayload);
    const eventType = String(payload.eventType || payload.type || "").toUpperCase();
    const planId = payload.planId || graph.value.planId;

    if (!planId && eventType.startsWith("PLAN_")) return;

    if (planId && !graph.value.planId) graph.value.planId = planId;
    if (planId && graph.value.planId !== planId) return;

    updatePlanMeta(payload);

    if (payload.message) runtimeMessage.value = String(payload.message);

    if (eventType === "PLAN_CREATED") {
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_PHASE_STARTED") {
      const phaseId = payload.phaseId || "unknown-phase";
      ensurePhase(phaseId, payload.phaseOrder ?? Number.MAX_SAFE_INTEGER);

      const phase = graph.value.phases[phaseId];
      const timestamp = payload.timestamp || nowTs();
      if (!phase.lastEventTs || timestamp >= phase.lastEventTs) {
        phase.status = "RUNNING";
        phase.phaseOrder = payload.phaseOrder ?? phase.phaseOrder;
        phase.lastEventTs = timestamp;
      }

      graph.value.phaseOrder = Object.values(graph.value.phases)
        .sort((left, right) => (left.phaseOrder ?? 0) - (right.phaseOrder ?? 0))
        .map((phaseItem) => phaseItem.phaseId);

      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_RUNNING") {
      upsertNode(payload, "RUNNING");
      refreshNodeStats();
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_SUCCESS") {
      upsertNode(payload, "SUCCESS");
      refreshNodeStats();

      const phaseId = payload.phaseId || graph.value.nodes[payload.nodeId]?.phaseId || "unknown-phase";
      ensurePhase(phaseId);
      const phase = graph.value.phases[phaseId];
      phase.successCount = payload.successCount ?? phase.successCount ?? 0;

      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_FAILED") {
      upsertNode(payload, "FAILED");
      refreshNodeStats();

      const phaseId = payload.phaseId || graph.value.nodes[payload.nodeId]?.phaseId || "unknown-phase";
      ensurePhase(phaseId);
      const phase = graph.value.phases[phaseId];
      phase.failCount = payload.failCount ?? phase.failCount ?? 0;

      errors.value = [
        {
          nodeId: payload.nodeId || "",
          phaseId,
          failReason: payload.failReason || "",
          errorCode: payload.errorCode || "",
          message: payload.message || "",
          timestamp: payload.timestamp || nowTs(),
        },
        ...errors.value,
      ].slice(0, 100);

      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_PHASE_FINISHED") {
      const phaseId = payload.phaseId || "unknown-phase";
      ensurePhase(phaseId, payload.phaseOrder ?? Number.MAX_SAFE_INTEGER);

      const phase = graph.value.phases[phaseId];
      const timestamp = payload.timestamp || nowTs();
      if (!phase.lastEventTs || timestamp >= phase.lastEventTs) {
        phase.status = normalizeStatus(payload.status || "FINISHED");
        phase.successCount = payload.successCount ?? phase.successCount ?? 0;
        phase.failCount = payload.failCount ?? phase.failCount ?? 0;
        phase.phaseOrder = payload.phaseOrder ?? phase.phaseOrder;
        phase.lastEventTs = timestamp;
      }

      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_FINISHED") {
      const nextStatus = normalizeStatus(payload.finalStatus || payload.status || graph.value.finalStatus || "SUCCESS");
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, nextStatus, false);
      lockPlanFinalState(planId);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_REPORT_READY") {
      report.value = {
        ready: true,
        reportPath: payload.reportPath || report.value.reportPath || "",
        reportUrl: payload.reportUrl || report.value.reportUrl || "",
        finalStatus: pickFinalStatus(payload) || report.value.finalStatus || graph.value.finalStatus || "",
      };
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType.startsWith("PLAN_")) {
      const fallbackStatus = eventType === "PLAN_BLUEPRINT_INVALID" ? "FAILED" : "RUNNING";
      runtimeStatus.value = normalizePlanStatusTransition(
        runtimeStatus.value,
        payload.status || fallbackStatus,
        locked.value,
      );
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "APPROVAL_REQUEST") {
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "APPROVAL_PENDING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "APPROVAL_RESULT") {
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "SKILL_ASYNC_RESULT" || eventType === "WORKFLOW_ASYNC_RESULT") {
      const asyncEvent = {
        taskId: payload.taskId || "",
        skillName: payload.skillName || payload.workflowName || "",
        workflowName: payload.workflowName || payload.skillName || "",
        status: payload.status || "",
        success: !!payload.success,
        message: payload.message || "",
        errorCode: payload.errorCode || "",
        error: payload.error || "",
        result: payload.result,
        costMs: payload.costMs,
        timestamp: payload.timestamp || nowTs(),
      };
      asyncEvents.value = [asyncEvent, ...asyncEvents.value].slice(0, 50);
    }
  }

  function toPlanPanelRuntime() {
    return {
      planId: graph.value.planId,
      status: runtimeStatus.value,
      finalStatus: graph.value.finalStatus || report.value.finalStatus || "",
      locked: locked.value,
      message: runtimeMessage.value,
      createdAt: graph.value.createdAt,
      updatedAt: graph.value.updatedAt || graph.value.lastSyncAt,
      phases: graph.value.phases,
      nodes: graph.value.nodes,
      report: report.value,
      edges: graph.value.edges,
      errors: errors.value,
      phaseOrder: graph.value.phaseOrder,
      nodeStats: graph.value.nodeStats,
      planVersion: graph.value.planVersion,
    };
  }

  return {
    graph,
    asyncEvents,
    report,
    runtimeStatus,
    runtimeMessage,
    errors,
    locked,
    reset,
    lockPlanFinalState,
    syncGraphSnapshot,
    replaceFromSnapshot,
    applyEvent,
    toPlanPanelRuntime,
  };
}
