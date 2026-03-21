<template>
  <div
    class="query-panel"
    :style="{ left: x + 'px', top: y + 'px', width: width + 'px', height: height + 'px' }"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="panel-header" @mousedown="startDrag">
      <span>DATA QUERY CENTER</span>
      <button class="close-btn" @click="$emit('close')">×</button>
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
                <td v-for="h in headers" :key="h">{{ stringifyCell(r[h]) }}</td>
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

    <div class="resize-handle sw" @mousedown.stop="startResize($event, 'sw')"></div>
    <div class="resize-handle se" @mousedown.stop="startResize($event, 'se')"></div>
    <div class="resize-handle nw" @mousedown.stop="startResize($event, 'nw')"></div>
    <div class="resize-handle ne" @mousedown.stop="startResize($event, 'ne')"></div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from "vue";
import { queryKnowledgeBase, queryUserPreference, queryMemory, queryLog } from "../api/index.js";

defineEmits(["close", "mouseenter", "mouseleave"]);

const tab = ref("kb");
const loading = ref(false);

const x = ref(window.innerWidth / 2 - 420);
const y = ref(window.innerHeight / 2 - 260);
const width = ref(840);
const height = ref(520);

const minWidth = 620;
const minHeight = 360;

let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
  if (e.target.closest(".close-btn")) return;
  isDragging = true;
  dragOffset.x = e.clientX - x.value;
  dragOffset.y = e.clientY - y.value;
  window.addEventListener("mousemove", onDrag);
  window.addEventListener("mouseup", stopDrag);
}
function onDrag(e) {
  if (!isDragging) return;
  x.value = e.clientX - dragOffset.x;
  y.value = e.clientY - dragOffset.y;
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

function resetFilters() {
  Object.keys(filters).forEach((k) => { filters[k] = ""; });
  rows.value = [];
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
    pager.total = res?.total || 0;
    pager.pages = res?.pages || 1;
    pager.pageNo = res?.pageNo || 1;
    pager.pageSize = res?.pageSize || pager.pageSize;
  } catch (e) {
    console.error("[QueryPanel] 查询失败", e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.query-panel {
  position: fixed;
  background: var(--bg-panel, rgba(5,10,19,0.95));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.8);
  backdrop-filter: blur(10px);
  color: var(--text-main, #fff);
  z-index: 9600;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  background: rgba(0,0,0,0.25);
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--primary, #00ffc8);
  font-weight: bold;
  letter-spacing: 1px;
  cursor: move;
  user-select: none;
}
.close-btn {
  border: none;
  background: none;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
}
.panel-body { display: flex; flex: 1; min-height: 0; }
.sidebar {
  width: 140px;
  border-right: 1px solid var(--border);
  background: var(--bg-sidebar, rgba(0,0,0,0.3));
  padding: 10px 0;
}
.menu-item {
  padding: 11px 14px;
  cursor: pointer;
  color: var(--text-dim, #9aa);
  border-left: 3px solid transparent;
}
.menu-item:hover { background: var(--hover, rgba(255,255,255,0.05)); color: var(--text-main, #fff); }
.menu-item.active {
  border-left-color: var(--primary, #00ffc8);
  color: var(--primary, #00ffc8);
  background: var(--primary-dim, rgba(0,255,200,0.1));
}
.content {
  flex: 1;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.filter-grid input, .pager select {
  background: rgba(0,0,0,0.35);
  border: 1px solid var(--border, rgba(255,255,255,0.15));
  color: var(--text-main, #fff);
  border-radius: 6px;
  padding: 7px 10px;
}
.actions { display: flex; justify-content: flex-end; gap: 8px; }
.btn-primary, .btn-secondary {
  border-radius: 6px;
  border: 1px solid var(--border);
  padding: 6px 12px;
  cursor: pointer;
}
.btn-primary { background: var(--primary, #00ffc8); color: #000; border-color: transparent; font-weight: bold; }
.btn-secondary { background: rgba(255,255,255,0.08); color: var(--text-main, #fff); }
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--border, rgba(255,255,255,0.1));
  border-radius: 8px;
}
.data-table { width: 100%; border-collapse: collapse; }
.data-table th, .data-table td {
  border-bottom: 1px solid rgba(255,255,255,0.08);
  padding: 8px 10px;
  text-align: left;
  font-size: 12px;
  vertical-align: top;
}
.data-table th {
  position: sticky;
  top: 0;
  background: rgba(0,0,0,0.5);
  color: var(--primary, #00ffc8);
}
.empty { text-align: center; color: var(--text-dim, #999); padding: 20px 0; }
.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--text-dim, #aaa);
  font-size: 12px;
}
.pager-actions { display: flex; align-items: center; gap: 8px; }

.resize-handle {
  position: absolute;
  width: 14px;
  height: 14px;
  z-index: 3;
}
.resize-handle.sw { left: 0; bottom: 0; cursor: sw-resize; }
.resize-handle.se { right: 0; bottom: 0; cursor: se-resize; }
.resize-handle.nw { left: 0; top: 0; cursor: nw-resize; }
.resize-handle.ne { right: 0; top: 0; cursor: ne-resize; }
</style>
