<template>
  <div
    class="query-panel"
    :class="{ fullscreen: isFullscreen }"
    :style="panelStyle"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="panel-header" @mousedown="startDrag">
      <span>DATA QUERY CENTER</span>
      <div class="header-actions">
        <button class="header-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
          {{ isFullscreen ? "🗗" : "🗖" }}
        </button>
        <button class="close-btn" @click="$emit('close')">×</button>
      </div>
    </div>

    <div class="panel-body">
      <div class="sidebar">
        <div class="menu-item" :class="{ active: tab === 'kb' }" @click="switchTab('kb')">知识库</div>
        <div class="menu-item" :class="{ active: tab === 'pref' }" @click="switchTab('pref')">用户偏好</div>
        <div class="menu-item" :class="{ active: tab === 'memory' }" @click="switchTab('memory')">长期记忆</div>
        <div class="menu-item" :class="{ active: tab === 'log' }" @click="switchTab('log')">日志</div>
      </div>

      <div class="content">
        <div class="filter-grid">
          <template v-if="tab === 'kb'">
            <input v-model="filters.title" placeholder="标题" />
            <input v-model="filters.content" placeholder="内容" />
            <input v-model="filters.sourceType" placeholder="来源类型(FILE/WEB_SEARCH/MANUAL_INPUT)" />
            <input v-model="filters.sourcePath" placeholder="来源路径" />
          </template>

          <template v-else-if="tab === 'pref'">
            <input v-model="filters.prefKey" placeholder="偏好键" />
            <input v-model="filters.prefValue" placeholder="偏好值" />
            <input v-model="filters.description" placeholder="描述" />
          </template>

          <template v-else-if="tab === 'memory'">
            <input v-model="filters.sessionId" placeholder="会话ID" />
            <input v-model="filters.memoryType" placeholder="类型(FACT/PREFERENCE/SUMMARY/REFLECTION)" />
            <input v-model="filters.content" placeholder="内容" />
            <input v-model.number="filters.minWeight" type="number" placeholder="最小权重" />
            <input v-model.number="filters.maxWeight" type="number" placeholder="最大权重" />
          </template>

          <template v-else>
            <input v-model="filters.logType" placeholder="日志类型" />
            <input v-model="filters.module" placeholder="模块" />
            <input v-model="filters.action" placeholder="动作" />
            <input v-model="filters.content" placeholder="内容" />
            <input v-model="filters.traceId" placeholder="TraceId" />
            <input v-model="filters.operatorId" placeholder="OperatorId" />
          </template>

          <input v-model="filters.startTime" placeholder="开始时间 yyyy-MM-dd HH:mm:ss" />
          <input v-model="filters.endTime" placeholder="结束时间 yyyy-MM-dd HH:mm:ss" />
        </div>

        <div class="actions">
          <button class="btn-secondary" @click="resetFilters">重置</button>
          <button class="btn-primary" @click="query(1)" :disabled="loading">{{ loading ? "查询中..." : "查询" }}</button>
        </div>

        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th v-for="h in headers" :key="h">{{ h }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!loading && rows.length === 0">
                <td :colspan="headers.length" class="empty">暂无数据</td>
              </tr>
              <tr v-for="(r, idx) in rows" :key="idx">
                <td v-for="h in headers" :key="h">
                  <div class="cell-content" :class="{ expanded: isExpanded(idx, h) }" :title="stringifyCell(r[h])">
                    {{ getCellDisplayText(r[h], idx, h) }}
                  </div>
                  <button v-if="shouldCollapse(r[h])" class="cell-toggle" @click="toggleExpand(idx, h)">
                    {{ isExpanded(idx, h) ? "收起" : "展开" }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pager">
          <span>共 {{ pager.total }} 条 / {{ pager.pages }} 页</span>
          <div class="pager-actions">
            <button class="btn-secondary" :disabled="pager.pageNo <= 1 || loading" @click="query(pager.pageNo - 1)">上一页</button>
            <span>{{ pager.pageNo }}</span>
            <button class="btn-secondary" :disabled="pager.pageNo >= pager.pages || loading" @click="query(pager.pageNo + 1)">下一页</button>
            <select v-model.number="pager.pageSize" @change="query(1)">
              <option :value="10">10</option>
              <option :value="20">20</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <template v-if="!isFullscreen">
      <div class="resize-handle sw" @mousedown.stop="startResize($event, 'sw')"></div>
      <div class="resize-handle se" @mousedown.stop="startResize($event, 'se')"></div>
      <div class="resize-handle nw" @mousedown.stop="startResize($event, 'nw')"></div>
      <div class="resize-handle ne" @mousedown.stop="startResize($event, 'ne')"></div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onBeforeUnmount } from "vue";
import { queryKnowledgeBase, queryUserPreference, queryMemory, queryLog } from "../api/index.js";

defineEmits(["close", "mouseenter", "mouseleave"]);

const tab = ref("kb");
const loading = ref(false);

const x = ref(window.innerWidth / 2 - 540);
const y = ref(window.innerHeight / 2 - 320);
const width = ref(1080);
const height = ref(640);

const minWidth = 760;
const minHeight = 460;

const isFullscreen = ref(false);
const prevRect = ref({ x: x.value, y: y.value, w: width.value, h: height.value });

const panelStyle = computed(() => {
  if (isFullscreen.value) return { left: "0px", top: "0px", width: "100vw", height: "100vh" };
  return { left: x.value + "px", top: y.value + "px", width: width.value + "px", height: height.value + "px" };
});

function saveRect() {
  prevRect.value = { x: x.value, y: y.value, w: width.value, h: height.value };
}
function toggleFullscreen() {
  if (!isFullscreen.value) {
    saveRect();
    isFullscreen.value = true;
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
  const maxX = window.innerWidth - 80;
  const maxY = window.innerHeight - 80;
  x.value = Math.min(Math.max(e.clientX - dragOffset.x, -width.value + 120), maxX);
  y.value = Math.min(Math.max(e.clientY - dragOffset.y, -60), maxY);
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
  if (isFullscreen.value) return;
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
  if (resizeDir.includes("n")) {
    const nh = Math.max(minHeight, initial.h - dy);
    y.value = initial.y + (initial.h - nh);
    height.value = nh;
  }
}
function stopResize() {
  window.removeEventListener("mousemove", onResize);
  window.removeEventListener("mouseup", stopResize);
}

const filters = reactive({});
const rows = ref([]);
const expandedMap = reactive({});
const pager = reactive({ total: 0, pages: 1, pageNo: 1, pageSize: 10 });

const headers = computed(() => {
  if (!rows.value.length) {
    if (tab.value === "kb") return ["id", "title", "content", "sourceType", "sourcePath", "createdAt"];
    if (tab.value === "pref") return ["id", "prefKey", "prefValue", "description", "createdAt"];
    if (tab.value === "memory") return ["id", "sessionId", "memoryType", "content", "weight", "createdAt"];
    return ["id", "logType", "module", "action", "content", "traceId", "operatorId", "createAt"];
  }
  return Object.keys(rows.value[0]);
});

function stringifyCell(v) {
  if (v === null || v === undefined) return "";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

function getCellKey(rowIdx, field) {
  return `${pager.pageNo}-${rowIdx}-${field}`;
}

function shouldCollapse(v) {
  const text = stringifyCell(v);
  return text.length > 120 || text.includes("\n");
}

function isExpanded(rowIdx, field) {
  return !!expandedMap[getCellKey(rowIdx, field)];
}

function toggleExpand(rowIdx, field) {
  const key = getCellKey(rowIdx, field);
  expandedMap[key] = !expandedMap[key];
}

function getCellDisplayText(v, rowIdx, field) {
  const text = stringifyCell(v);
  if (!shouldCollapse(v)) return text;
  if (isExpanded(rowIdx, field)) return text;
  return text.slice(0, 120) + "...";
}

function resetExpandedState() {
  Object.keys(expandedMap).forEach((k) => delete expandedMap[k]);
}

function resetFilters() {
  Object.keys(filters).forEach((k) => { filters[k] = ""; });
  rows.value = [];
  resetExpandedState();
  pager.total = 0;
  pager.pages = 1;
  pager.pageNo = 1;
}

function switchTab(t) {
  tab.value = t;
  resetFilters();
}

function buildPayload(pageNo) {
  const payload = {
    pageNo,
    pageSize: pager.pageSize,
  };
  Object.keys(filters).forEach((k) => {
    const v = filters[k];
    if (v !== "" && v !== null && v !== undefined) payload[k] = v;
  });
  return payload;
}

async function query(pageNo = 1) {
  loading.value = true;
  try {
    const payload = buildPayload(pageNo);
    let res;
    if (tab.value === "kb") res = await queryKnowledgeBase(payload);
    else if (tab.value === "pref") res = await queryUserPreference(payload);
    else if (tab.value === "memory") res = await queryMemory(payload);
    else res = await queryLog(payload);

    rows.value = res?.records || [];
    resetExpandedState();
    pager.total = res?.total || 0;
    pager.pages = res?.pages || 1;
    pager.pageNo = res?.pageNo || 1;
    pager.pageSize = res?.pageSize || pager.pageSize;
  } catch (e) {
    console.error("[QueryPanel] 查询失败", e);
    rows.value = [];
    resetExpandedState();
  } finally {
    loading.value = false;
  }
}

onBeforeUnmount(() => {
  stopDrag();
  stopResize();
});
</script>

<style scoped>
.query-panel {
  position: fixed;
  background:
    radial-gradient(circle at top right, rgba(0, 255, 200, 0.1), transparent 35%),
    radial-gradient(circle at bottom left, rgba(0, 170, 255, 0.08), transparent 30%),
    linear-gradient(145deg, rgba(8,14,26,0.95), rgba(5,10,19,0.95));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 12px;
  box-shadow:
    0 24px 70px rgba(0,0,0,0.78),
    0 0 24px rgba(0,255,200,0.14),
    0 0 0 1px rgba(255,255,255,0.03) inset;
  backdrop-filter: blur(12px) saturate(118%);
  color: var(--text-main, #fff);
  z-index: 9600;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.query-panel::before {
  content: "";
  position: absolute;
  inset: -30% -15%;
  background: conic-gradient(from 45deg, rgba(0,255,200,0.04), transparent 45%, rgba(0,170,255,0.04), transparent 80%);
  animation: panelAura 11s linear infinite;
  pointer-events: none;
}
.query-panel.fullscreen {