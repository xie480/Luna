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
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
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

const filters = reactive({
  title: "",
  content: "",
  sourceType: "",
  sourcePath: "",
  prefKey: "",
  prefValue: "",
  description: "",
  sessionId: "",
  memoryType: "",
  minWeight: "",
  maxWeight: "",
  logType: "",
  module: "",
  action: "",
  traceId: "",
  operatorId: "",
  startTime: "",
  endTime: "",
});
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
  Object.keys(filters).forEach((k) => {
    filters[k] = "";
  });
  rows.value = [];
  resetExpandedState();
  pager.total = 0;
  pager.pages = 1;
  pager.pageNo = 1;
}

function switchTab(t) {
  tab.value = t;
  resetFilters();
  query(1);
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

function normalizeResponse(res) {
  if (!res) return { records: [], total: 0, pages: 1, pageNo: 1, pageSize: pager.pageSize };

  if (Array.isArray(res)) {
    return {
      records: res,
      total: res.length,
      pages: 1,
      pageNo: 1,
      pageSize: pager.pageSize,
    };
  }

  const data = res.data ?? res;
  if (Array.isArray(data)) {
    return {
      records: data,
      total: data.length,
      pages: 1,
      pageNo: 1,
      pageSize: pager.pageSize,
    };
  }

  return {
    records: data?.records || data?.list || [],
    total: data?.total ?? 0,
    pages: data?.pages ?? 1,
    pageNo: data?.pageNo ?? 1,
    pageSize: data?.pageSize ?? pager.pageSize,
  };
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

    const parsed = normalizeResponse(res);
    rows.value = parsed.records;
    resetExpandedState();
    pager.total = parsed.total;
    pager.pages = parsed.pages || 1;
    pager.pageNo = parsed.pageNo || pageNo;
    pager.pageSize = parsed.pageSize || pager.pageSize;
  } catch (e) {
    console.error("[QueryPanel] 查询失败", e);
    rows.value = [];
    resetExpandedState();
    pager.total = 0;
    pager.pages = 1;
    pager.pageNo = 1;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  query(1);
});

onBeforeUnmount(() => {
  stopDrag();
  stopResize();
});
</script>

<style scoped>
.query-panel {
  position: fixed;
  background: var(--bg-panel, linear-gradient(145deg, rgba(8,14,26,0.95), rgba(5,10,19,0.95)));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 12px;
  box-shadow: var(--shadow-panel, 0 24px 70px rgba(0,0,0,0.78));
  backdrop-filter: blur(12px) saturate(118%);
  color: var(--text-main, #fff);
  z-index: 9600;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.query-panel.fullscreen {
  border-radius: 0;
}

.panel-header {
  padding: 12px 15px;
  background: color-mix(in oklab, black 76%, transparent);
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: bold;
  letter-spacing: 1px;
  color: var(--primary, #00ffc8);
  cursor: move;
  user-select: none;
  z-index: 2;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-btn {
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.2)) 72%, transparent);
  background: var(--bg-panel-soft, rgba(255,255,255,0.08));
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
  box-shadow: var(--glow-primary, 0 0 8px rgba(0,255,200,0.22));
}
.close-btn {
  background: none;
  border: none;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
  transition: transform 0.2s;
}
.close-btn:hover {
  transform: scale(1.08);
}

.panel-body {
  flex: 1;
  display: flex;
  overflow: hidden;
  z-index: 1;
}

.sidebar {
  width: 132px;
  background: color-mix(in oklab, var(--bg-sidebar, rgba(0,0,0,0.35)) 100%, transparent);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 10px 0;
}
.menu-item {
  padding: 12px 14px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-dim, #888);
  transition: 0.2s;
  border-left: 3px solid transparent;
}
.menu-item:hover {
  background: var(--hover, rgba(255,255,255,0.05));
  color: var(--text-main, #fff);
}
.menu-item.active {
  background: linear-gradient(
    90deg,
    color-mix(in oklab, var(--primary, #00ffc8) 18%, transparent),
    color-mix(in oklab, var(--primary-2, #00aaff) 10%, transparent)
  );
  color: var(--primary, #00ffc8);
  border-left-color: var(--primary, #00ffc8);
  box-shadow: inset 0 0 8px color-mix(in oklab, var(--primary, #00ffc8) 18%, transparent);
}

.content {
  flex: 1;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  min-height: 0;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.filter-grid input {
  width: 100%;
  background: color-mix(in oklab, black 70%, transparent);
  border: 1px solid var(--border, #2d3c4d);
  color: var(--text-main, #fff);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.btn-primary,
.btn-secondary {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 12px;
}
.btn-primary {
  background: var(--primary, #00ffc8);
  color: #000;
  border-color: transparent;
  font-weight: bold;
}
.btn-primary:hover:not(:disabled) {
  filter: brightness(1.08);
}
.btn-secondary {
  background: var(--bg-panel-soft, rgba(255,255,255,0.08));
  color: var(--text-main, #fff);
}
.btn-secondary:hover:not(:disabled) {
  border-color: var(--primary, #00ffc8);
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  border-radius: 8px;
  background: var(--bg-panel-soft, rgba(255,255,255,0.03));
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.data-table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  text-align: left;
  background: color-mix(in oklab, black 72%, transparent);
  color: var(--primary, #00ffc8);
  padding: 8px;
  border-bottom: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.1)) 50%, transparent);
  white-space: nowrap;
}
.data-table tbody td {
  padding: 8px;
  border-bottom: 1px dashed color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 40%, transparent);
  vertical-align: top;
}
.empty {
  text-align: center;
  color: var(--text-dim, #88a);
  padding: 16px 0;
}

.cell-content {
  max-height: 3.8em;
  overflow: hidden;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.45;
}
.cell-content.expanded {
  max-height: none;
}
.cell-toggle {
  margin-top: 4px;
  border: none;
  background: none;
  color: var(--primary, #00ffc8);
  cursor: pointer;
  font-size: 11px;
  padding: 0;
}
.cell-toggle:hover {
  text-decoration: underline;
}

.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--text-dim, #9fb0bc);
  font-size: 12px;
}
.pager-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pager select {
  background: color-mix(in oklab, black 68%, transparent);
  border: 1px solid var(--border);
  color: var(--text-main, #fff);
  border-radius: 4px;
  padding: 4px 6px;
  font-size: 12px;
}

.resize-handle {
  position: absolute;
  width: 14px;
  height: 14px;
  z-index: 10;
}
.resize-handle.sw {
  left: 0;
  bottom: 0;
  cursor: sw-resize;
}
.resize-handle.se {
  right: 0;
  bottom: 0;
  cursor: se-resize;
}
.resize-handle.nw {
  left: 0;
  top: 0;
  cursor: nw-resize;
}
.resize-handle.ne {
  right: 0;
  top: 0;
  cursor: ne-resize;
}

.content::-webkit-scrollbar,
.table-wrap::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
.content::-webkit-scrollbar-track,
.table-wrap::-webkit-scrollbar-track {
  background: var(--bg-panel-soft, rgba(255,255,255,0.05));
  border-radius: 8px;
}
.content::-webkit-scrollbar-thumb,
.table-wrap::-webkit-scrollbar-thumb {
  background: linear-gradient(
    180deg,
    color-mix(in oklab, var(--primary, #00ffc8) 45%, transparent),
    color-mix(in oklab, var(--primary-2, #00aaff) 45%, transparent)
  );
  border-radius: 8px;
}
.content::-webkit-scrollbar-thumb:hover,
.table-wrap::-webkit-scrollbar-thumb:hover {
  background: linear-gradient(
    180deg,
    color-mix(in oklab, var(--primary, #00ffc8) 68%, transparent),
    color-mix(in oklab, var(--primary-2, #00aaff) 65%, transparent)
  );
}

@media (max-width: 1100px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
