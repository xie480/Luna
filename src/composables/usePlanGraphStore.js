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

function normalizeNodeStatusTransition(prevStatus, nextStatus) {
  const p = normalizeStatus(prevStatus);
  const n = normalizeStatus(nextStatus);

  // 防止 SUCCESS 回退 RUNNING
  if (p === "SUCCESS" && n === "RUNNING") return p;
  // 防止 FAILED 回退 RUNNING（除非有重试机制，这里保守不回退）
  if (p === "FAILED" && n === "RUNNING") return p;

  return n;
}

function readRecordsFromSnapshot(snapshot) {
  const data = snapshot?.data ?? snapshot ?? {};
  if (Array.isArray(data.records)) return data.records;
  if (Array.isArray(data.nodes)) return data.nodes;
  if (Array.isArray(data.items)) return data.items;
  if (Array.isArray(data.phases)) return data.phases;
  return [];
}

function normalizeSnapshot(snapshot, planIdHint = "") {
  const data = snapshot?.data ?? snapshot ?? {};
  const planId = data.planId || planIdHint || "";

  const phases = {};
  const nodes = {};
  let edges = [];

  // 兼容后端多种结构
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

  // phaseOrder
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
  const errors = ref([]);

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
    errors.value = [];
  }

  function replaceFromSnapshot(snapshot, planIdHint = "") {
    const next = normalizeSnapshot(snapshot, planIdHint);
    graph.value = next;
    if (next.planId) runtimeStatus.value = "RUNNING";
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
    if (!planId) return;

    if (!graph.value.planId) graph.value.planId = planId;
    if (graph.value.planId !== planId) return;

    if (eventType === "PLAN_CREATED") {
      runtimeStatus.value = "RUNNING";
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

      runtimeStatus.value = "RUNNING";
      graph.value.lastSyncAt = nowTs();
      return;
    }

    if (eventType === "PLAN_NODE_RUNNING") {
      upsertNode(p, "RUNNING");
      runtimeStatus.value = "RUNNING";
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

    if (eventType === "PLAN_REPORT_READY") {
      report.value = {
        ready: true,
        reportPath: p.reportPath || report.value.reportPath || "",
        reportUrl: p.reportUrl || report.value.reportUrl || "",
      };
      runtimeStatus.value = "REPORT_READY";
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
    errors,
    reset,
    syncGraphSnapshot,
    replaceFromSnapshot,
    applyEvent,
    toPlanPanelRuntime,
  };
}
