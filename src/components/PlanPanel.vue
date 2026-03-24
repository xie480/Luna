<template>
  <div
    class="plan-panel-shell"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div
      class="plan-panel"
      :class="{ fullscreen: isFullscreen, minimized: isMinimized }"
      :style="panelStyle"
    >
      <div class="panel-header" @mousedown="startDrag">
        <div class="title-wrap">
          <span class="title-main">OPENCLAW PLAN CENTER</span>
          <span class="title-sub">Event Driven Runtime Console</span>
        </div>
        <div class="header-right">
          <span class="status-dot" :class="statusDotClass" :title="runtime?.status || 'IDLE'"></span>
          <button class="header-btn" @click.stop="toggleMinimize" :title="isMinimized ? '还原' : '最小化'">
            {{ isMinimized ? "▢" : "—" }}
          </button>
          <button class="header-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
            {{ isFullscreen ? "🗗" : "🗖" }}
          </button>
          <button class="close-btn" @click="$emit('close')">×</button>
        </div>
      </div>

      <div v-show="!isMinimized" class="panel-body">
        <div class="top-form card">
          <textarea
            v-model="userGoal"
            :disabled="isRunning || runtime?.locked"
            placeholder="请输入用户目标（userGoal），例如：帮我整理项目并生成报告"
          ></textarea>

          <div class="form-actions">
            <button class="btn-primary" :disabled="isRunning || runtime?.locked || !userGoal.trim()" @click="runPlan">
              <span v-if="isRunning" class="btn-spinner"></span>
              {{ isRunning ? "执行中..." : "执行计划" }}
            </button>
            <button class="btn-secondary" :disabled="!runtime.planId" @click="copyReportPath">
              复制报告路径
            </button>
            <button class="btn-secondary" :disabled="!getReportTarget()" @click="openReport">
              打开任务报告
            </button>
          </div>
        </div>

        <transition name="toast-fade">
          <div v-if="feedback.show" class="feedback-toast" :class="feedback.type">
            {{ feedback.text }}
          </div>
        </transition>

        <div class="summary card">
          <span>计划ID：{{ runtime.planId || "-" }}</span>
          <span>状态：{{ runtime.status || "-" }}</span>
          <span>锁定：{{ runtime.locked ? "是" : "否" }}</span>
          <span>消息：{{ runtime.message || "-" }}</span>
          <span>更新时间：{{ formatTs(runtime.updatedAt) }}</span>
        </div>

        <div class="graph card auto-card neon-card">
          <div class="col-title">计划图谱（Phase 分栏）</div>
          <div class="graph-scroll">
            <div class="phase-lanes">
              <div
                v-for="phase in orderedPhases"
                :key="phase.phaseId"
                class="phase-lane"
              >
                <div class="phase-head">
                  <div class="phase-name">{{ phase.name || phase.phaseId }}</div>
                  <div class="phase-meta">
                    <span>#{{ phase.phaseOrder ?? "-" }}</span>
                    <span class="badge" :class="badgeClass(phase.status)">{{ phase.status || "PENDING" }}</span>
                  </div>
                  <div class="phase-count">S: {{ phase.successCount ?? 0 }} / F: {{ phase.failCount ?? 0 }}</div>
                </div>

                <div class="phase-nodes">
                  <div
                    v-for="node in getNodesByPhase(phase.phaseId)"
                    :key="node.nodeId"
                    class="node-card"
                    :class="badgeClass(node.status)"
                  >
                    <div class="node-top">
                      <b>{{ node.skillName || node.nodeName || node.nodeId }}</b>
                      <span class="badge" :class="badgeClass(node.status)">{{ node.status || "PENDING" }}</span>
                    </div>
                    <div class="node-sub">nodeId: {{ node.nodeId }}</div>
                    <div class="node-sub">nodeType: {{ node.nodeType || "-" }}</div>
                    <div class="node-sub">costMs: {{ node.costMs ?? 0 }} · retry: {{ node.retryCount ?? 0 }}</div>
                    <div v-if="node.status === 'FAILED'" class="fail-reason">
                      失败原因：{{ node.failReason || node.errorCode || node.message || "-" }}
                    </div>
                    <div v-else class="output-summary">
                      输出摘要：{{ summarizeOutput(node.outputForNext) }}
                    </div>
                  </div>
                </div>
              </div>

              <div v-if="orderedPhases.length === 0" class="empty">暂无图谱数据</div>
            </div>
          </div>
        </div>

        <div class="edges-box card auto-card neon-card">
          <div class="col-title">边关系（from → to）</div>
          <div class="list">
            <div v-for="(e, idx) in runtimeEdges" :key="idx" class="item">
              <div class="line">
                <b>{{ e.fromNodeId || "-" }}</b>
                <span>→</span>
                <b>{{ e.toNodeId || "-" }}</b>
              </div>
              <div class="sub">condition: {{ e.conditionExpr || "-" }}</div>
            </div>
            <div v-if="runtimeEdges.length === 0" class="empty">暂无边关系</div>
          </div>
        </div>

        <div class="async-box card auto-card neon-card">
          <div class="col-title">异步技能事件</div>
          <div class="list">
            <div v-for="(e, idx) in asyncEvents" :key="idx" class="item">
              <div class="line">
                <b>{{ e.skillName || "-" }}</b>
                <span class="badge" :class="badgeClass(e.status)">{{ e.status || "-" }}</span>
              </div>
              <div class="sub">taskId: {{ e.taskId || "-" }} · costMs: {{ e.costMs ?? "-" }}</div>
              <div class="msg">{{ e.message || e.errorCode || e.error || "-" }}</div>
            </div>
            <div v-if="asyncEvents.length === 0" class="empty">暂无异步事件</div>
          </div>
        </div>
      </div>

      <template v-if="!isFullscreen && !isMinimized">
        <div class="resize-handle se" @mousedown.stop="startResize($event, 'se')"></div>
        <div class="resize-handle sw" @mousedown.stop="startResize($event, 'sw')"></div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onBeforeUnmount } from "vue";
import { planRun, openExternal } from "../api/index.js";

const props = defineProps({
  runtime: { type: Object, required: true },
  asyncEvents: { type: Array, default: () => [] },
});
const emit = defineEmits(["close", "mouseenter", "mouseleave", "run-created"]);

const userGoal = ref("");
const isRunning = ref(false);

const feedback = ref({
  show: false,
  type: "info",
  text: "",
});
let feedbackTimer = null;

function showFeedback(text, type = "info", duration = 2200) {
  feedback.value = { show: true, type, text };
  if (feedbackTimer) clearTimeout(feedbackTimer);
  feedbackTimer = setTimeout(() => {
    feedback.value.show = false;
  }, duration);
}

const x = ref(window.innerWidth / 2 - 520);
const y = ref(window.innerHeight / 2 - 320);
const width = ref(1040);
const height = ref(640);
const minWidth = 780;
const minHeight = 460;

const isMinimized = ref(false);
const isFullscreen = ref(false);
const prevRect = ref({ x: x.value, y: y.value, w: width.value, h: height.value });

const panelStyle = computed(() => {
  if (isFullscreen.value) {
    return { left: "0px", top: "0px", width: "100vw", height: "100vh" };
  }
  return { left: x.value + "px", top: y.value + "px", width: width.value + "px", height: isMinimized.value ? "52px" : height.value + "px" };
});

function saveRect() {
  prevRect.value = { x: x.value, y: y.value, w: width.value, h: height.value };
}

function toggleMinimize() {
  isMinimized.value = !isMinimized.value;
}

function toggleFullscreen() {
  if (!isFullscreen.value) {
    saveRect();
    isFullscreen.value = true;
    isMinimized.value = false;
  } else {
    isFullscreen.value = false;
    x.value = prevRect.value.x;
    y.value = prevRect.value.y;
    width.value = prevRect.value.w;
    height.value = prevRect.value.h;
  }
}

let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
  if (isFullscreen.value) return;
  if (e.target.closest(".close-btn") || e.target.closest(".header-btn")) return;
  isDragging = true;
  dragOffset.x = e.clientX - x.value;
  dragOffset.y = e.clientY - y.value;
  window.addEventListener("mousemove", onDrag);
  window.addEventListener("mouseup", stopDrag);
}
function onDrag(e) {
  if (!isDragging) return;
  x.value = Math.min(Math.max(e.clientX - dragOffset.x, -width.value + 120), window.innerWidth - 80);
  y.value = Math.min(Math.max(e.clientY - dragOffset.y, -40), window.innerHeight - 80);
}
function stopDrag() {
  isDragging = false;
  window.removeEventListener("mousemove", onDrag);
  window.removeEventListener("mouseup", stopDrag);
}

let resizeDir = "";
let resizeStart = { x: 0, y: 0 };
let initial = { x: 0, y: 0, w: 0, h: 0 };

function startResize(e, dir) {
  if (isFullscreen.value || isMinimized.value) return;
  resizeDir = dir;
  resizeStart = { x: e.clientX, y: e.clientY };
  initial = { x: x.value, y: y.value, w: width.value, h: height.value };
  window.addEventListener("mousemove", onResize);
  window.addEventListener("mouseup", stopResize);
}
function onResize(e) {
  const dx = e.clientX - resizeStart.x;
  const dy = e.clientY - resizeStart.y;

  if (resizeDir.includes("e")) width.value = Math.max(minWidth, initial.w + dx);
  if (resizeDir.includes("s")) height.value = Math.max(minHeight, initial.h + dy);

  if (resizeDir.includes("w")) {
    const nw = Math.max(minWidth, initial.w - dx);
    x.value = initial.x + (initial.w - nw);
    width.value = nw;
  }
}
function stopResize() {
  window.removeEventListener("mousemove", onResize);
  window.removeEventListener("mouseup", stopResize);
}

const orderedPhases = computed(() => {
  const phases = props.runtime?.phases || {};
  const arr = Object.values(phases);
  return arr.sort((a, b) => (a.phaseOrder ?? 0) - (b.phaseOrder ?? 0));
});

const allNodes = computed(() => {
  const nodes = props.runtime?.nodes || {};
  return Object.values(nodes).sort((a, b) => (a.timestamp ?? 0) - (b.timestamp ?? 0));
});

const runtimeEdges = computed(() => {
  const edges = props.runtime?.edges;
  return Array.isArray(edges) ? edges : [];
});

function getNodesByPhase(phaseId) {
  return allNodes.value.filter((n) => n.phaseId === phaseId);
}

const statusDotClass = computed(() => {
  const s = String(props.runtime?.status || "").toUpperCase();
  if (["SUCCESS", "COMPLETED", "FINISHED", "REPORT_READY"].includes(s)) return "ok";
  if (["FAILED", "ERROR"].includes(s)) return "err";
  if (["RUNNING", "THINKING"].includes(s)) return "run";
  if (["APPROVAL_PENDING"].includes(s)) return "wait";
  return "";
});

function formatTs(ts) {
  if (!ts) return "-";
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return String(ts);
  }
}

function badgeClass(status) {
  const s = String(status || "").toUpperCase();
  if (["SUCCESS", "COMPLETED", "FINISHED", "REPORT_READY"].includes(s)) return "ok";
  if (["FAILED", "ERROR"].includes(s)) return "err";
  if (["RUNNING", "THINKING"].includes(s)) return "run";
  if (["APPROVAL_PENDING"].includes(s)) return "wait";
  return "";
}

function summarizeOutput(v) {
  if (v === null || v === undefined) return "-";
  if (typeof v === "string") return v.length > 120 ? v.slice(0, 120) + "..." : v;
  try {
    const s = JSON.stringify(v);
    return s.length > 120 ? s.slice(0, 120) + "..." : s;
  } catch {
    return String(v);
  }
}

async function runPlan() {
  if (!userGoal.value.trim() || isRunning.value) return;
  isRunning.value = true;
  showFeedback("计划请求已发出，正在执行…", "info", 1800);

  try {
    const res = await planRun({ userGoal: userGoal.value.trim() });
    const data = res?.data ?? res ?? {};
    const planId = data.planId || res?.planId || "";
    if (!planId) {
      throw new Error("后端未返回 planId");
    }

    emit("run-created", { planId, runResponse: res });
    showFeedback("计划已创建，正在拉取图谱并订阅实时更新", "success", 2400);
  } catch (e) {
    const msg = e?.message || String(e);
    showFeedback(`计划执行失败：${msg}`, "error", 3200);
  } finally {
    isRunning.value = false;
  }
}

function getReportTarget() {
  const report = props.runtime?.report || {};
  return report.reportUrl || report.reportPath || "";
}

async function openReport() {
  const target = getReportTarget();
  if (!target) {
    showFeedback("暂无可打开的报告路径", "error", 1800);
    return;
  }
  try {
    let finalTarget = target;
    if (!/^https?:\/\//i.test(target) && !/^file:\/\//i.test(target)) {
      finalTarget = `file:///${target.replace(/\\/g, "/")}`;
    }
    await openExternal(finalTarget);
    showFeedback("已尝试打开任务报告", "success", 1800);
  } catch (e) {
    showFeedback("打开报告失败: " + (e?.message || String(e)), "error", 2800);
  }
}

async function copyReportPath() {
  const report = props.runtime?.report || {};
  const text = report.reportPath || report.reportUrl || "";
  if (!text) {
    showFeedback("暂无可复制的报告路径", "error", 1600);
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    showFeedback("报告路径已复制", "success", 1400);
  } catch {
    const ta = document.createElement("textarea");
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    document.body.removeChild(ta);
    showFeedback("报告路径已复制", "success", 1400);
  }
}

onBeforeUnmount(() => {
  if (feedbackTimer) clearTimeout(feedbackTimer);
  stopDrag();
  stopResize();
});
</script>

<style scoped>
.plan-panel-shell {
  position: fixed;
  inset: 0;
  z-index: 9800;
  pointer-events: none;
}

.plan-panel {
  position: fixed;
  background:
    radial-gradient(circle at top right, rgba(0, 255, 200, 0.12), transparent 35%),
    radial-gradient(circle at bottom left, rgba(0, 130, 255, 0.1), transparent 30%),
    linear-gradient(145deg, rgba(7,12,24,0.96), rgba(4,8,18,0.96));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.75), 0 0 0 1px rgba(255,255,255,0.03) inset;
  color: var(--text-main, #fff);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  pointer-events: auto;
}
.plan-panel::before {
  content: "";
  position: absolute;
  inset: -35%;
  background: radial-gradient(circle, rgba(0,255,200,0.05), transparent 60%);
  animation: panelGlow 7s linear infinite;
  pointer-events: none;
}
.plan-panel.fullscreen {
  border-radius: 0;
}
.plan-panel.minimized {
  overflow: hidden;
}

.panel-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(180deg, rgba(0,0,0,0.35), rgba(0,0,0,0.16));
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: move;
  user-select: none;
  z-index: 2;
}
.title-wrap { display: flex; flex-direction: column; gap: 2px; }
.title-main {
  color: var(--primary, #00ffc8);
  font-weight: bold;
  letter-spacing: 1px;
  font-size: 13px;
}
.title-sub {
  font-size: 11px;
  color: var(--text-dim, #8aa);
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-btn {
  border: 1px solid rgba(255,255,255,0.2);
  background: rgba(255,255,255,0.08);
  color: var(--text-main, #fff);
  width: 24px;
  height: 22px;
  border-radius: 4px;
  cursor: pointer;
  line-height: 1;
  transition: 0.2s;
}
.header-btn:hover {
  border-color: var(--primary, #00ffc8);
  box-shadow: 0 0 8px rgba(0,255,200,0.25);
}
.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #7c8792;
  box-shadow: 0 0 6px rgba(124, 135, 146, 0.45);
}
.status-dot.ok { background: #22c55e; box-shadow: 0 0 8px rgba(34,197,94,0.65); }
.status-dot.err { background: #ef4444; box-shadow: 0 0 8px rgba(239,68,68,0.65); }
.status-dot.run { background: #3b82f6; box-shadow: 0 0 8px rgba(59,130,246,0.65); }
.status-dot.wait { background: #f59e0b; box-shadow: 0 0 8px rgba(245,158,11,0.65); }

.close-btn {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
  transition: transform 0.2s;
}
.close-btn:hover {
  transform: scale(1.08);
}

.panel-body {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow-y: auto;
  overflow-x: auto;
  z-index: 1;
}

.card {
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px;
  background: rgba(255,255,255,0.03);
}
.neon-card {
  box-shadow: inset 0 0 12px rgba(0,255,200,0.05);
}
.auto-card {
  width: max-content;
  min-width: 100%;
}

.top-form {
  padding: 10px;
}
.top-form textarea {
  width: 100%;
  min-height: 80px;
  background: rgba(0,0,0,0.38);
  border: 1px solid var(--border);
  color: var(--text-main, #fff);
  border-radius: 6px;
  padding: 10px;
  resize: vertical;
}
.form-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.btn-primary, .btn-secondary {
  border-radius: 6px;
  padding: 7px 12px;
  border: 1px solid var(--border);
  cursor: pointer;
}
.btn-primary {
  background: var(--primary, #00ffc8);
  color: #000;
  border-color: transparent;
  font-weight: bold;
  display: inline-flex;
  align-items: center;
}
.btn-primary:hover:not(:disabled) {
  filter: brightness(1.08);
}
.btn-secondary {
  background: rgba(255,255,255,0.08);
  color: var(--text-main, #fff);
}
.btn-secondary:hover:not(:disabled) {
  border-color: var(--primary, #00ffc8);
}
.btn-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(0,0,0,0.35);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-right: 6px;
}

.summary {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  color: var(--text-dim, #aaa);
  font-size: 12px;
  padding: 8px 10px;
}

.feedback-toast {
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
  border: 1px solid;
}
.feedback-toast.info {
  background: rgba(59,130,246,0.14);
  border-color: rgba(59,130,246,0.4);
  color: #93c5fd;
}
.feedback-toast.success {
  background: rgba(34,197,94,0.14);
  border-color: rgba(34,197,94,0.4);
  color: #86efac;
}
.feedback-toast.error {
  background: rgba(239,68,68,0.14);
  border-color: rgba(239,68,68,0.4);
  color: #fca5a5;
}

.graph {
  min-height: 260px;
}
.col-title {
  padding: 8px 10px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  color: var(--primary, #00ffc8);
  font-size: 12px;
  font-weight: bold;
}
.graph-scroll {
  padding: 8px;
  overflow: auto;
}
.phase-lanes {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  min-height: 180px;
  width: max-content;
}
.phase-lane {
  min-width: 260px;
  max-width: 420px;
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px;
  background: rgba(255,255,255,0.03);
}
.phase-head {
  padding: 8px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.phase-name {
  font-weight: bold;
  font-size: 13px;
}
.phase-meta {
  margin-top: 6px;
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 11px;
  color: #9fb0bc;
}
.phase-count {
  margin-top: 4px;
  font-size: 11px;
  color: #a7bac8;
}
.phase-nodes {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.node-card {
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 6px;
  padding: 8px;
  background: rgba(255,255,255,0.02);
  transition: transform 0.2s ease, box-shadow 0.25s ease;
}
.node-card:hover {
  transform: translateY(-1px);
}
.node-card.run { border-color: rgba(59,130,246,0.5); box-shadow: 0 0 10px rgba(59,130,246,0.18); }
.node-card.ok { border-color: rgba(34,197,94,0.5); box-shadow: 0 0 10px rgba(34,197,94,0.18); }
.node-card.err { border-color: rgba(239,68,68,0.5); box-shadow: 0 0 10px rgba(239,68,68,0.18); }
.node-card.wait { border-color: rgba(245,158,11,0.5); box-shadow: 0 0 10px rgba(245,158,11,0.18); }

.node-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.node-sub {
  margin-top: 4px;
  font-size: 11px;
  color: #9fb0bc;
}
.fail-reason {
  margin-top: 6px;
  font-size: 12px;
  color: #fca5a5;
  word-break: break-word;
}
.output-summary {
  margin-top: 6px;
  font-size: 12px;
  color: #d8e8ef;
  word-break: break-word;
}

.edges-box {
  min-height: 120px;
}

.list {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
  width: max-content;
  min-width: 100%;
}
.item {
  border: 1px solid rgba(255,255,255,0.08);
  background: rgba(255,255,255,0.03);
  border-radius: 6px;
  padding: 8px;
  min-width: 420px;
}
.line {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}
.sub {
  margin-top: 4px;
  font-size: 11px;
  color: #9fb0bc;
}
.msg {
  margin-top: 4px;
  font-size: 12px;
  color: #d8e8ef;
  word-break: break-word;
}
.badge {
  font-size: 11px;
  border-radius: 4px;
  padding: 2px 6px;
  border: 1px solid rgba(255,255,255,0.2);
}
.badge.ok { color: #9ae6b4; border-color: rgba(0,255,120,0.5); }
.badge.err { color: #fc8181; border-color: rgba(255,0,0,0.5); }
.badge.run { color: #63b3ed; border-color: rgba(0,150,255,0.5); }
.badge.wait { color: #fcd34d; border-color: rgba(245,158,11,0.5); }

.empty {
  color: var(--text-dim, #888);
  font-size: 12px;
  text-align: center;
  padding: 10px 0;
}
.async-box {
  min-height: 130px;
}

.resize-handle {
  position: absolute;
  width: 14px;
  height: 14px;
  bottom: 0;
}
.resize-handle.se { right: 0; cursor: se-resize; }
.resize-handle.sw { left: 0; cursor: sw-resize; }

.panel-body::-webkit-scrollbar,
.list::-webkit-scrollbar,
.graph-scroll::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
.panel-body::-webkit-scrollbar-track,
.list::-webkit-scrollbar-track,
.graph-scroll::-webkit-scrollbar-track {
  background: rgba(255,255,255,0.05);
  border-radius: 8px;
}
.panel-body::-webkit-scrollbar-thumb,
.list::-webkit-scrollbar-thumb,
.graph-scroll::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, rgba(0,255,200,0.45), rgba(0,180,255,0.45));
  border-radius: 8px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
@keyframes panelGlow {
  0% { transform: translate(-10%, -10%) rotate(0deg); }
  100% { transform: translate(10%, 10%) rotate(360deg); }
}
.toast-fade-enter-active, .toast-fade-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.toast-fade-enter-from, .toast-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
