import { ref } from "vue";
import { planGraph } from "../api/index.js";

function nowTs() {
  return Date.now();
}

function toObj(v) {
  return v && typeof v === "object" ? v : {};
}

function normalizeStatus(s) {
  const t = String(s || "").toUpperCase();
  if (!t) return "PENDING";
  return t;
}

function normalizePlanStatusTransition(prevStatus, nextStatus, locked) {
  const prev = normalizeStatus(prevStatus || "PENDING");
  const next = normalizeStatus(nextStatus || prev);

  if (locked) return prev;

  // 终态锁定：SUCCESS/FAILED 不应被 RUNNING 回退
  const terminal = ["SUCCESS", "FAILED"];
  if (terminal.includes(prev) && next === "RUNNING") return prev;

  return next;
}

function normalizeNodeStatusTransition(prevStatus, nextStatus) {
  const p = normalizeStatus(prevStatus);
  const n = normalizeStatus(nextStatus);

  if (p === "SUCCESS" && n === "RUNNING") return p;
  if (p === "FAILED" && n === "RUNNING") return p;

  return n;
}

function normalizeSnapshot(snapshot, planIdHint = "") {
  const data = snapshot?.data ?? snapshot ?? {};
  const planId = data.planId || planIdHint || "";

  const phases = {};
  const nodes = {};
  let edges = [];

  const phaseList = Array.isArray(data.phases) ? data.phases : [];
  const nodeList = Array.isArray(data.nodes) ? data.nodes : [];
  const rawEdges = Array.isArray(data.edges) ? data.edges : [];

  phaseList.forEach((p, idx) => {
    const phaseId = p.phaseId || p.id || `phase-${idx + 1}`;
    phases[phaseId] = {
      phaseId,
      phaseOrder: p.phaseOrder ?? p.order ?? idx,
      name: p.name || phaseId,
      status: normalizeStatus(p.status || "PENDING"),
      successCount: p.successCount ?? 0,
      failCount: p.failCount ?? 0,
      nodeIds: Array.isArray(p.nodeIds) ? p.nodeIds.slice() : [],
      lastEventTs: p.timestamp || p.updatedAt || 0,
    };
  });

  nodeList.forEach((n, idx) => {
    const nodeId = n.nodeId || n.id || `node-${idx + 1}`;
    const phaseId = n.phaseId || n.phase || "unknown-phase";
    nodes[nodeId] = {
      nodeId,
      phaseId,
      nodeName: n.nodeName || n.name || nodeId,
      skillName: n.skillName || n.nodeName || n.name || "unknown-node",
      nodeType: n.nodeType || "",
      status: normalizeStatus(n.status || "PENDING"),
      failReason: n.failReason || "",
      errorCode: n.errorCode || "",
      retryCount: n.retryCount ?? 0,
      costMs: n.costMs ?? 0,
      outputForNext: n.outputForNext ?? {},
      message: n.message || "",
      timestamp: n.timestamp || n.updatedAt || 0,
      lastEventTs: n.timestamp || n.updatedAt || 0,
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

  edges = rawEdges.map((e, idx) => ({
    fromNodeId: e.fromNodeId || e.from || e.source || `unknown-from-${idx}`,
    toNodeId: e.toNodeId || e.to || e.target || `unknown-to-${idx}`,
    conditionExpr: e.conditionExpr || "",
  }));

  const phaseOrder = Object.values(phases)
    .sort((a, b) => (a.phaseOrder ?? 0) - (b.phaseOrder ?? 0))
    .map((p) => p.phaseId);

  return {
    planId,
    phases,
    phaseOrder,
    nodes,
    edges,
    lastSyncAt: nowTs(),
    status: normalizeStatus(data.status || "RUNNING"),
    message: data.message || "",
    reportPath: data.reportPath || "",
    reportUrl: data.reportUrl || "",
  };
}

export function usePlanGraphStore() {
  const graph = ref({
    planId: "",
    phases: {},
    phaseOrder: [],
    nodes: {},
    edges: [],
    lastSyncAt: 0,
  });

  const asyncEvents = ref([]);
  const report = ref({ ready: false, reportPath: "", reportUrl: "" });
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
      lastSyncAt: nowTs(),
    };
    asyncEvents.value = [];
    report.value = { ready: false, reportPath: "", reportUrl: "" };
    runtimeStatus.value = planId ? "RUNNING" : "IDLE";
    runtimeMessage.value = "";
    errors.value = [];
    locked.value = false;
  }

  function lockPlanFinalState(planId = "") {
    if (planId && graph.value.planId && graph.value.planId !== planId) return;
    locked.value = true;
  }

  function replaceFromSnapshot(snapshot, planIdHint = "") {
    const next = normalizeSnapshot(snapshot, planIdHint);
    graph.value = {
      planId: next.planId,
      phases: next.phases,
      phaseOrder: next.phaseOrder,
      nodes: next.nodes,
      edges: next.edges,
      lastSyncAt: next.lastSyncAt,
    };

    runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, next.status, locked.value);
    runtimeMessage.value = next.message || runtimeMessage.value || "";

    if (next.reportPath || next.reportUrl) {
      report.value = {
        ready: true,
        reportPath: next.reportPath || "",
        reportUrl: next.reportUrl || "",
      };
    }
  }

  async function syncGraphSnapshot(planId) {
    if (!planId) return;
    const snapshot = await planGraph(planId);
    replaceFromSnapshot(snapshot, planId);
  }

  function ensurePhase(phaseId, phaseOrder = Number.MAX_SAFE_INTEGER) {
    const g = graph.value;
    if (!g.phases[phaseId]) {
      g.phases[phaseId] = {
        phaseId,
        phaseOrder,
        name: phaseId,
        status: "PENDING",
        successCount: 0,
        failCount: 0,
        nodeIds: [],
        lastEventTs: 0,
      };
      g.phaseOrder = Object.values(g.phases)
        .sort((a, b) => (a.phaseOrder ?? 0) - (b.phaseOrder ?? 0))
        .map((p) => p.phaseId);
    }
  }

  function upsertNode(payload, statusHint = "") {
    const g = graph.value;
    const ts = payload.timestamp || nowTs();
    const nodeId = payload.nodeId || `node-${ts}`;
    const phaseId = payload.phaseId || "unknown-phase";
    ensurePhase(phaseId, payload.phaseOrder ?? Number.MAX_SAFE_INTEGER);

    const prev = g.nodes[nodeId] || {
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

    if (prev.lastEventTs && ts < prev.lastEventTs) {
      return;
    }

    const nextStatus = statusHint || payload.status || prev.status;
    const status = normalizeNodeStatusTransition(prev.status, nextStatus);

    g.nodes[nodeId] = {
      ...prev,
      phaseId,
      nodeName: payload.nodeName || prev.nodeName || nodeId,
      skillName: payload.skillName || payload.nodeName || prev.skillName || "unknown-node",
      nodeType: payload.nodeType || prev.nodeType || "",
      status,
      failReason: payload.failReason ?? prev.failReason ?? "",
      errorCode: payload.errorCode ?? prev.errorCode ?? "",
      retryCount: payload.retryCount ?? prev.retryCount ?? 0,
      costMs: payload.costMs ?? prev.costMs ?? 0,
      outputForNext: payload.outputForNext ?? prev.outputForNext ?? {},
      message: payload.message ?? prev.message ?? "",
      timestamp: ts,
      lastEventTs: ts,
    };

    if (!g.phases[phaseId].nodeIds.includes(nodeId)) {
      g.phases[phaseId].nodeIds.push(nodeId);
    }
  }

  function applyEvent(eventPayload) {
    const p = toObj(eventPayload);
    const eventType = String(p.eventType || p.type || "").toUpperCase();
    const planId = p.planId || graph.value.planId;
    if (!planId && eventType.startsWith("PLAN_")) return;

    if (planId && !graph.value.planId) graph.value.planId = planId;
    if (planId && graph.value.planId !== planId) return;

    if (p.message) runtimeMessage.value = String(p.message);

    if (eventType === "PLAN_CREATED") {
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_PHASE_STARTED") {
      const phaseId = p.phaseId || "unknown-phase";
      ensurePhase(phaseId, p.phaseOrder ?? Number.MAX_SAFE_INTEGER);

      const phase = graph.value.phases[phaseId];
      const ts = p.timestamp || nowTs();
      if (!phase.lastEventTs || ts >= phase.lastEventTs) {
        phase.status = "RUNNING";
        phase.phaseOrder = p.phaseOrder ?? phase.phaseOrder;
        phase.lastEventTs = ts;
      }

      graph.value.phaseOrder = Object.values(graph.value.phases)
        .sort((a, b) => (a.phaseOrder ?? 0) - (b.phaseOrder ?? 0))
        .map((x) => x.phaseId);

      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_RUNNING") {
      upsertNode(p, "RUNNING");
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, "RUNNING", locked.value);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_SUCCESS") {
      upsertNode(p, "SUCCESS");
      const phaseId = p.phaseId || graph.value.nodes[p.nodeId]?.phaseId || "unknown-phase";
      ensurePhase(phaseId);
      const phase = graph.value.phases[phaseId];
      phase.successCount = p.successCount ?? phase.successCount ?? 0;
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_FAILED") {
      upsertNode(p, "FAILED");
      const phaseId = p.phaseId || graph.value.nodes[p.nodeId]?.phaseId || "unknown-phase";
      ensurePhase(phaseId);
      const phase = graph.value.phases[phaseId];
      phase.failCount = p.failCount ?? phase.failCount ?? 0;

      errors.value = [{
        nodeId: p.nodeId || "",
        phaseId,
        failReason: p.failReason || "",
        errorCode: p.errorCode || "",
        message: p.message || "",
        timestamp: p.timestamp || nowTs(),
      }, ...errors.value].slice(0, 100);

      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_PHASE_FINISHED") {
      const phaseId = p.phaseId || "unknown-phase";
      ensurePhase(phaseId, p.phaseOrder ?? Number.MAX_SAFE_INTEGER);
      const phase = graph.value.phases[phaseId];
      const ts = p.timestamp || nowTs();
      if (!phase.lastEventTs || ts >= phase.lastEventTs) {
        phase.status = normalizeStatus(p.status || "FINISHED");
        phase.successCount = p.successCount ?? phase.successCount ?? 0;
        phase.failCount = p.failCount ?? phase.failCount ?? 0;
        phase.phaseOrder = p.phaseOrder ?? phase.phaseOrder;
        phase.lastEventTs = ts;
      }
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_FINISHED") {
      const next = normalizeStatus(p.status || "SUCCESS");
      runtimeStatus.value = normalizePlanStatusTransition(runtimeStatus.value, next, false);
      lockPlanFinalState(planId);
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_REPORT_READY") {
      report.value = {
        ready: true,
        reportPath: p.reportPath || report.value.reportPath || "",
        reportUrl: p.reportUrl || report.value.reportUrl || "",
      };
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

    if (eventType === "SKILL_ASYNC_RESULT") {
      const evt = {
        taskId: p.taskId || "",
        skillName: p.skillName || "",
        status: p.status || "",
        success: !!p.success,
        message: p.message || "",
        errorCode: p.errorCode || "",
        error: p.error || "",
        result: p.result,
        costMs: p.costMs,
        timestamp: p.timestamp || nowTs(),
      };
      asyncEvents.value = [evt, ...asyncEvents.value].slice(0, 50);
    }
  }

  function toPlanPanelRuntime() {
    return {
      planId: graph.value.planId,
      status: runtimeStatus.value,
      locked: locked.value,
      message: runtimeMessage.value,
      createdAt: 0,
      updatedAt: graph.value.lastSyncAt,
      phases: graph.value.phases,
      nodes: graph.value.nodes,
      report: report.value,
      edges: graph.value.edges,
      errors: errors.value,
      phaseOrder: graph.value.phaseOrder,
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
