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
        <div class="menu-item" :class="{ active: tab === 'log' }" @click="switchTab('log')">运行日志</div>
        <div class="menu-item" :class="{ active: tab === 'rag' }" @click="switchTab('rag')">RAG 调试</div>
      </div>

      <div class="content">
        <transition name="toast-fade">
          <div v-if="panelMessage.text" class="panel-message" :class="panelMessage.type">{{ panelMessage.text }}</div>
        </transition>

        <template v-if="tab !== 'rag'">
          <div class="filter-grid">
            <template v-if="tab === 'kb'">
              <input v-model.trim="kbFilters.title" placeholder="标题" />
              <input v-model.trim="kbFilters.content" placeholder="内容" />
              <select v-model="kbFilters.sourceType">
                <option value="">来源类型（全部）</option>
                <option value="FILE">FILE</option>
                <option value="WEB_SEARCH">WEB_SEARCH</option>
                <option value="MANUAL_INPUT">MANUAL_INPUT</option>
              </select>
              <input v-model.trim="kbFilters.sourcePath" placeholder="来源路径" />
            </template>

            <template v-else>
              <select v-model="logFilters.logType">
                <option value="">日志类型（全部）</option>
                <option value="LUNA_OUTPUT">LUNA_OUTPUT</option>
                <option value="TOOL_CALL">TOOL_CALL</option>
                <option value="ERROR">ERROR</option>
                <option value="SELF_UPDATE">SELF_UPDATE</option>
                <option value="SYSTEM_EVENT">SYSTEM_EVENT</option>
                <option value="API_CALL">API_CALL</option>
              </select>
              <input v-model.trim="logFilters.module" placeholder="模块" />
              <input v-model.trim="logFilters.action" placeholder="动作" />
              <input v-model.trim="logFilters.content" placeholder="内容" />
              <input v-model.trim="logFilters.traceId" placeholder="TraceId" />
              <input v-model.trim="logFilters.operatorId" placeholder="OperatorId" />
            </template>

            <input v-model.trim="activeFilters.startTime" placeholder="开始时间 yyyy-MM-dd HH:mm:ss" />
            <input v-model.trim="activeFilters.endTime" placeholder="结束时间 yyyy-MM-dd HH:mm:ss" />
          </div>

          <div class="actions">
            <button class="btn-secondary" @click="resetCurrentFilters">重置</button>
            <button class="btn-primary" @click="query(currentPager.pageNo || 1)" :disabled="tableLoading">
              {{ tableLoading ? "查询中..." : "查询" }}
            </button>
          </div>

          <div class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th v-for="column in tableHeaders" :key="column">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!tableLoading && rows.length === 0">
                  <td :colspan="tableHeaders.length" class="empty">暂无数据</td>
                </tr>
                <tr v-for="(row, rowIndex) in rows" :key="row.id || `${currentPager.pageNo}-${rowIndex}`">
                  <td v-for="column in tableHeaders" :key="column">
                    <div class="cell-content" :class="{ expanded: isExpanded(rowIndex, column) }" :title="stringifyCell(row[column])">
                      {{ getCellDisplayText(row[column], rowIndex, column) }}
                    </div>
                    <button v-if="shouldCollapse(row[column])" class="cell-toggle" @click="toggleExpand(rowIndex, column)">
                      {{ isExpanded(rowIndex, column) ? "收起" : "展开" }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="pager">
            <span>共 {{ currentPager.total }} 条 / {{ currentPager.pages }} 页</span>
            <div class="pager-actions">
              <button class="btn-secondary" :disabled="currentPager.pageNo <= 1 || tableLoading" @click="query(currentPager.pageNo - 1)">上一页</button>
              <span>{{ currentPager.pageNo }}</span>
              <button class="btn-secondary" :disabled="currentPager.pageNo >= currentPager.pages || tableLoading" @click="query(currentPager.pageNo + 1)">下一页</button>
              <select v-model.number="currentPager.pageSize" @change="query(1)">
                <option :value="10">10</option>
                <option :value="20">20</option>
                <option :value="50">50</option>
                <option :value="100">100</option>
                <option :value="200">200</option>
              </select>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="rag-layout">
            <section class="rag-form card-box">
              <div class="section-head">
                <div>
                  <strong>检索请求</strong>
                  <small>按文档字段构造 RAG 调试载荷</small>
                </div>
              </div>

              <div class="rag-scroll">
                <label class="form-block">
                  <span>query</span>
                  <textarea v-model.trim="ragForm.query" class="field code-input" placeholder="请输入原始检索问题"></textarea>
                </label>

                <div class="two-col">
                  <label class="form-block">
                    <span>sessionId</span>
                    <input v-model.trim="ragForm.sessionId" class="field" placeholder="可选，会话 ID" />
                  </label>
                  <label class="form-block">
                    <span>options.maxLatencyMs</span>
                    <input v-model.number="ragForm.maxLatencyMs" type="number" min="1" class="field" placeholder="2500" />
                  </label>
                </div>
                <label class="form-block">
                  <span>conversationContext</span>
                  <textarea
                    v-model="ragForm.conversationContextText"
                    class="field code-input tall"
                    placeholder='JSON 数组，例如 [{"role":"user","content":"你好"}]'
                  ></textarea>
                </label>

                <div class="two-col">
                  <label class="form-block">
                    <span>allowedRoutes</span>
                    <textarea v-model="ragForm.allowedRoutesText" class="field code-input" placeholder="search,native,modular,agentic"></textarea>
                  </label>
                  <label class="form-block">
                    <span>sourceScope</span>
                    <textarea v-model="ragForm.sourceScopeText" class="field code-input" placeholder="knowledge,memory,preference"></textarea>
                  </label>
                </div>

                <label class="inline-check">
                  <input v-model="ragForm.debug" type="checkbox" />
                  <span>options.debug</span>
                </label>

                <div class="actions form-actions">
                  <button class="btn-secondary" @click="resetRagForm">重置</button>
                  <button class="btn-primary" :disabled="ragLoading" @click="runRagRetrieve">
                    {{ ragLoading ? "检索中..." : "执行检索" }}
                  </button>
                </div>
              </div>
            </section>

            <section class="rag-result card-box">
              <div class="section-head">
                <div>
                  <strong>检索结果</strong>
                  <small>改写查询 / 证据命中 / 角色分组 / Meta</small>
                </div>
                <StatusBadge :label="ragSummaryLabel" :tone="ragResult?.meta?.status === 'rejected' ? 'WARNING' : 'RUNNING'" />
              </div>

              <div class="rag-scroll">
                <div v-if="!ragResult && !ragLoading" class="empty-state">输入 query 后执行检索，这里会展示命中路由、改写 query 与证据详情。</div>

                <template v-else>
                  <div class="summary-strip">
                    <div class="summary-card">
                      <span class="summary-label">命中路由</span>
                      <strong>{{ ragResult?.route || "-" }}</strong>
                    </div>
                    <div class="summary-card wide">
                      <span class="summary-label">rewrittenQuery</span>
                      <strong>{{ ragResult?.rewrittenQuery || ragResult?.meta?.governedQuery || "-" }}</strong>
                    </div>
                  </div>

                  <div v-if="ragResult?.meta?.status === 'rejected'" class="governance-blocked">
                    <strong>治理拒绝</strong>
                    <p>{{ ragResult?.meta?.reason || "治理阶段未生成可执行 query。" }}</p>
                  </div>

                  <div class="evidence-grid">
                    <div class="evidence-column">
                      <div class="col-title">知识库证据</div>
                      <EvidenceCard v-for="item in ragResult?.evidences?.knowledge || []" :key="`knowledge-${item.id || item.title}`" :evidence="item" />
                      <div v-if="!(ragResult?.evidences?.knowledge || []).length" class="empty-mini">暂无知识库证据</div>
                    </div>

                    <div class="evidence-column">
                      <div class="col-title">记忆证据</div>
                      <EvidenceCard v-for="item in ragResult?.evidences?.memory || []" :key="`memory-${item.id || item.title}`" :evidence="item" />
                      <div v-if="!(ragResult?.evidences?.memory || []).length" class="empty-mini">暂无记忆证据</div>
                    </div>

                    <div class="evidence-column">
                      <div class="col-title">偏好证据</div>
                      <EvidenceCard v-for="item in ragResult?.evidences?.preference || []" :key="`preference-${item.id || item.title}`" :evidence="item" />
                      <div v-if="!(ragResult?.evidences?.preference || []).length" class="empty-mini">暂无偏好证据</div>
                    </div>
                  </div>

                  <JsonPreviewBlock title="角色分组 / evidenceRoleGroups" :value="ragResult?.evidenceRoleGroups || {}" :max-height="220" />
                  <JsonPreviewBlock title="Meta 调试信息" :value="ragResult?.meta || {}" :max-height="260" />
                </template>
              </div>
            </section>
          </div>
        </template>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { queryKnowledgeBase, queryLog, ragRetrieve } from "../api/index.js";
import { normalizeRagResponse, parseTextArray, safeJsonParse } from "../utils/data-utils.js";
import EvidenceCard from "./common/EvidenceCard.vue";
import JsonPreviewBlock from "./common/JsonPreviewBlock.vue";
import StatusBadge from "./common/StatusBadge.vue";

defineEmits(["close", "mouseenter", "mouseleave"]);

const tab = ref("kb");
const tableLoading = ref(false);
const ragLoading = ref(false);
const rows = ref([]);
const ragResult = ref(null);
const expandedMap = reactive({});
const panelMessage = reactive({ text: "", type: "info" });
let panelMessageTimer = null;

const x = ref(window.innerWidth / 2 - 540);
const y = ref(window.innerHeight / 2 - 320);
const width = ref(1080);
const height = ref(640);
const minWidth = 820;
const minHeight = 500;
const isFullscreen = ref(false);
const prevRect = ref({ x: x.value, y: y.value, w: width.value, h: height.value });

const panelStyle = computed(() => {
  if (isFullscreen.value) return { left: "0px", top: "0px", width: "100vw", height: "100vh" };
  return { left: `${x.value}px`, top: `${y.value}px`, width: `${width.value}px`, height: `${height.value}px` };
});

function showPanelMessage(text, type = "info", duration = 2600) {
  panelMessage.text = text;
  panelMessage.type = type;
  if (panelMessageTimer) clearTimeout(panelMessageTimer);
  panelMessageTimer = setTimeout(() => {
    panelMessage.text = "";
  }, duration);
}

function saveRect() {
  prevRect.value = { x: x.value, y: y.value, w: width.value, h: height.value };
}

function toggleFullscreen() {
  if (!isFullscreen.value) {
    saveRect();
    isFullscreen.value = true;
    return;
  }
  isFullscreen.value = false;
  x.value = prevRect.value.x;
  y.value = prevRect.value.y;
  width.value = prevRect.value.w;
  height.value = prevRect.value.h;
}

let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(event) {
  if (isFullscreen.value) return;
  if (event.target.closest(".close-btn") || event.target.closest(".header-btn")) return;
  isDragging = true;
  dragOffset = { x: event.clientX - x.value, y: event.clientY - y.value };
  window.addEventListener("mousemove", onDrag);
  window.addEventListener("mouseup", stopDrag);
}

function onDrag(event) {
  if (!isDragging) return;
  const maxX = window.innerWidth - 80;
  const maxY = window.innerHeight - 80;
  x.value = Math.min(Math.max(event.clientX - dragOffset.x, -width.value + 120), maxX);
  y.value = Math.min(Math.max(event.clientY - dragOffset.y, -60), maxY);
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener("mousemove", onDrag);
  window.removeEventListener("mouseup", stopDrag);
}

let resizeDir = "";
let resizeStart = { x: 0, y: 0 };
let initialRect = { x: 0, y: 0, w: 0, h: 0 };

function startResize(event, dir) {
  if (isFullscreen.value) return;
  resizeDir = dir;
  resizeStart = { x: event.clientX, y: event.clientY };
  initialRect = { x: x.value, y: y.value, w: width.value, h: height.value };
  window.addEventListener("mousemove", onResize);
  window.addEventListener("mouseup", stopResize);
}

function onResize(event) {
  const dx = event.clientX - resizeStart.x;
  const dy = event.clientY - resizeStart.y;

  if (resizeDir.includes("e")) width.value = Math.max(minWidth, initialRect.w + dx);
  if (resizeDir.includes("s")) height.value = Math.max(minHeight, initialRect.h + dy);

  if (resizeDir.includes("w")) {
    const nextWidth = Math.max(minWidth, initialRect.w - dx);
    x.value = initialRect.x + (initialRect.w - nextWidth);
    width.value = nextWidth;
  }

  if (resizeDir.includes("n")) {
    const nextHeight = Math.max(minHeight, initialRect.h - dy);
    y.value = initialRect.y + (initialRect.h - nextHeight);
    height.value = nextHeight;
  }
}

function stopResize() {
  window.removeEventListener("mousemove", onResize);
  window.removeEventListener("mouseup", stopResize);
}

const kbFilters = reactive({
  title: "",
  content: "",
  sourceType: "",
  sourcePath: "",
  startTime: "",
  endTime: "",
});

const logFilters = reactive({
  logType: "",
  module: "",
  action: "",
  content: "",
  traceId: "",
  operatorId: "",
  startTime: "",
  endTime: "",
});

const kbPager = reactive({ total: 0, pages: 1, pageNo: 1, pageSize: 10 });
const logPager = reactive({ total: 0, pages: 1, pageNo: 1, pageSize: 10 });

const ragForm = reactive({
  query: "",
  sessionId: "",
  conversationContextText: "",
  allowedRoutesText: "",
  sourceScopeText: "",
  debug: true,
  maxLatencyMs: 2500,
});

const activeFilters = computed(() => (tab.value === "kb" ? kbFilters : logFilters));
const currentPager = computed(() => (tab.value === "kb" ? kbPager : logPager));
const tableHeaders = computed(() => {
  if (rows.value.length) return Object.keys(rows.value[0]);
  if (tab.value === "kb") {
    return ["id", "docId", "chunkId", "chunkOrder", "title", "content", "sourceType", "sourcePath", "createdAt", "updatedAt"];
  }
  return ["id", "logType", "module", "action", "content", "traceId", "operatorId", "costTime", "createAt"];
});

const ragSummaryLabel = computed(() => {
  if (!ragResult.value) return "等待检索";
  if (ragResult.value?.meta?.status === "rejected") return "治理拒绝";
  return ragResult.value.route || "已返回";
});

function resetExpandedState() {
  Object.keys(expandedMap).forEach((key) => delete expandedMap[key]);
}

function stringifyCell(value) {
  if (value === null || value === undefined) return "";
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function getCellKey(rowIndex, field) {
  return `${tab.value}-${currentPager.value.pageNo}-${rowIndex}-${field}`;
}

function shouldCollapse(value) {
  const text = stringifyCell(value);
  return text.length > 120 || text.includes("\n");
}

function isExpanded(rowIndex, field) {
  return !!expandedMap[getCellKey(rowIndex, field)];
}

function toggleExpand(rowIndex, field) {
  const key = getCellKey(rowIndex, field);
  expandedMap[key] = !expandedMap[key];
}

function getCellDisplayText(value, rowIndex, field) {
  const text = stringifyCell(value);
  if (!shouldCollapse(value) || isExpanded(rowIndex, field)) return text;
  return `${text.slice(0, 120)}...`;
}

function normalizePagedResponse(response, fallbackPageSize) {
  if (!response) {
    return { records: [], total: 0, pages: 1, pageNo: 1, pageSize: fallbackPageSize };
  }

  const data = response.data ?? response;
  if (Array.isArray(data)) {
    return {
      records: data,
      total: data.length,
      pages: 1,
      pageNo: 1,
      pageSize: fallbackPageSize,
    };
  }

  return {
    records: Array.isArray(data?.records) ? data.records : Array.isArray(data?.list) ? data.list : [],
    total: Number(data?.total ?? 0),
    pages: Number(data?.pages ?? 1),
    pageNo: Number(data?.pageNo ?? 1),
    pageSize: Number(data?.pageSize ?? fallbackPageSize),
  };
}

function buildPayload(pageNo) {
  const pager = currentPager.value;
  const payload = {
    pageNo,
    pageSize: pager.pageSize,
  };
  Object.entries(activeFilters.value).forEach(([key, value]) => {
    if (value !== "" && value !== null && value !== undefined) {
      payload[key] = value;
    }
  });
  return payload;
}

async function query(pageNo = 1) {
  if (tab.value === "rag") return;
  tableLoading.value = true;
  try {
    const payload = buildPayload(pageNo);
    const response = tab.value === "kb" ? await queryKnowledgeBase(payload) : await queryLog(payload);
    const parsed = normalizePagedResponse(response, currentPager.value.pageSize);
    rows.value = parsed.records;
    resetExpandedState();

    const pager = currentPager.value;
    pager.total = parsed.total;
    pager.pages = Math.max(parsed.pages || 1, 1);
    pager.pageNo = parsed.pageNo || pageNo;
    pager.pageSize = parsed.pageSize || pager.pageSize;
  } catch (error) {
    console.error("[QueryPanel] 查询失败", error);
    rows.value = [];
    resetExpandedState();
    currentPager.value.total = 0;
    currentPager.value.pages = 1;
    currentPager.value.pageNo = 1;
    showPanelMessage(error?.message || "查询失败，请稍后重试", "error", 3200);
  } finally {
    tableLoading.value = false;
  }
}

function resetCurrentFilters() {
  Object.keys(activeFilters.value).forEach((key) => {
    activeFilters.value[key] = "";
  });
  rows.value = [];
  resetExpandedState();
  currentPager.value.total = 0;
  currentPager.value.pages = 1;
  currentPager.value.pageNo = 1;
}

function switchTab(nextTab) {
  tab.value = nextTab;
  resetExpandedState();
  panelMessage.text = "";
  if (nextTab === "rag") return;
  rows.value = [];
  query(1);
}

function resetRagForm() {
  ragForm.query = "";
  ragForm.sessionId = "";
  ragForm.conversationContextText = "";
  ragForm.allowedRoutesText = "";
  ragForm.sourceScopeText = "";
  ragForm.debug = true;
  ragForm.maxLatencyMs = 2500;
  ragResult.value = null;
}

function buildRagPayload() {
  const queryText = String(ragForm.query || "").trim();
  if (!queryText) {
    throw new Error("query 不能为空");
  }

  const payload = {
    query: queryText,
    options: {
      debug: !!ragForm.debug,
      maxLatencyMs: Number(ragForm.maxLatencyMs) > 0 ? Number(ragForm.maxLatencyMs) : 2500,
    },
  };

  const sessionId = String(ragForm.sessionId || "").trim();
  if (sessionId) payload.sessionId = sessionId;

  const conversationContextText = String(ragForm.conversationContextText || "").trim();
  if (conversationContextText) {
    const parsedContext = safeJsonParse(conversationContextText, undefined);
    if (!Array.isArray(parsedContext)) {
      throw new Error("conversationContext 必须是 JSON 数组");
    }
    payload.conversationContext = parsedContext;
  }

  const allowedRoutes = parseTextArray(ragForm.allowedRoutesText);
  if (allowedRoutes.length) payload.allowedRoutes = allowedRoutes;

  const sourceScope = parseTextArray(ragForm.sourceScopeText);
  if (sourceScope.length) payload.sourceScope = sourceScope;

  return payload;
}

async function runRagRetrieve() {
  ragLoading.value = true;
  try {
    const payload = buildRagPayload();
    const response = await ragRetrieve(payload);
    ragResult.value = normalizeRagResponse(response);

    if (ragResult.value?.meta?.status === "rejected") {
      showPanelMessage(ragResult.value?.meta?.reason || "治理阶段拒绝了本次检索", "warn", 3200);
    } else {
      showPanelMessage("RAG 检索完成", "success", 1800);
    }
  } catch (error) {
    console.error("[QueryPanel] RAG 检索失败", error);
    ragResult.value = null;
    showPanelMessage(error?.message || "RAG 检索失败，请检查请求参数", "error", 3200);
  } finally {
    ragLoading.value = false;
  }
}

onMounted(() => {
  query(1);
});

onBeforeUnmount(() => {
  stopDrag();
  stopResize();
  if (panelMessageTimer) clearTimeout(panelMessageTimer);
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

.panel-message {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid;
  font-size: 12px;
}

.panel-message.info {
  background: rgba(59,130,246,0.14);
  border-color: rgba(59,130,246,0.4);
  color: #93c5fd;
}

.panel-message.success {
  background: rgba(34,197,94,0.14);
  border-color: rgba(34,197,94,0.4);
  color: #86efac;
}

.panel-message.warn {
  background: rgba(245,158,11,0.14);
  border-color: rgba(245,158,11,0.4);
  color: #fcd34d;
}

.panel-message.error {
  background: rgba(239,68,68,0.14);
  border-color: rgba(239,68,68,0.4);
  color: #fca5a5;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.filter-grid input,
.filter-grid select,
.field {
  width: 100%;
  background: color-mix(in oklab, black 70%, transparent);
  border: 1px solid var(--border, #2d3c4d);
  color: var(--text-main, #fff);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  box-sizing: border-box;
}

.filter-grid input:focus,
.filter-grid select:focus,
.field:focus,
textarea:focus {
  outline: none;
  border-color: var(--primary, #00ffc8);
  box-shadow: 0 0 0 1px color-mix(in oklab, var(--primary, #00ffc8) 26%, transparent);
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

.rag-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(320px, 0.92fr) minmax(0, 1.48fr);
  gap: 10px;
}

.card-box {
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent);
  border-radius: 10px;
  background: rgba(255,255,255,0.03);
  overflow: hidden;
}

.section-head {
  padding: 10px 12px;
  border-bottom: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.section-head strong {
  display: block;
  color: var(--primary, #00ffc8);
  font-size: 12px;
}

.section-head small {
  color: var(--text-dim, #8fa5b3);
  font-size: 11px;
}

.rag-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.form-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-block span,
.col-title,
.summary-label {
  color: var(--text-dim, #8fa5b3);
  font-size: 11px;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.code-input {
  resize: vertical;
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  line-height: 1.55;
  min-height: 88px;
}

.code-input.tall {
  min-height: 150px;
}

.inline-check {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-main, #eefaf5);
  font-size: 12px;
}

.inline-check input {
  accent-color: var(--primary, #00ffc8);
}

.form-actions {
  margin-top: auto;
}

.empty-state,
.empty-mini {
  color: var(--text-dim, #8fa5b3);
  font-size: 12px;
  text-align: center;
  padding: 18px 12px;
  border: 1px dashed color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent);
  border-radius: 10px;
}

.empty-mini {
  padding: 10px;
}

.summary-strip {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 8px;
}

.summary-card {
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255,255,255,0.04);
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-card strong {
  color: var(--text-main, #eefaf5);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.summary-card.wide {
  min-width: 0;
}

.governance-blocked {
  padding: 12px;
  border-radius: 10px;
  background: rgba(245,158,11,0.1);
  border: 1px solid rgba(245,158,11,0.35);
  color: #fcd34d;
}

.governance-blocked strong {
  display: block;
  margin-bottom: 6px;
}

.governance-blocked p {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.evidence-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.evidence-column {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.col-title {
  font-weight: 700;
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
.table-wrap::-webkit-scrollbar,
.rag-scroll::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.content::-webkit-scrollbar-track,
.table-wrap::-webkit-scrollbar-track,
.rag-scroll::-webkit-scrollbar-track {
  background: var(--bg-panel-soft, rgba(255,255,255,0.05));
  border-radius: 8px;
}

.content::-webkit-scrollbar-thumb,
.table-wrap::-webkit-scrollbar-thumb,
.rag-scroll::-webkit-scrollbar-thumb {
  background: linear-gradient(
    180deg,
    color-mix(in oklab, var(--primary, #00ffc8) 45%, transparent),
    color-mix(in oklab, var(--primary-2, #00aaff) 45%, transparent)
  );
  border-radius: 8px;
}

.content::-webkit-scrollbar-thumb:hover,
.table-wrap::-webkit-scrollbar-thumb:hover,
.rag-scroll::-webkit-scrollbar-thumb:hover {
  background: linear-gradient(
    180deg,
    color-mix(in oklab, var(--primary, #00ffc8) 68%, transparent),
    color-mix(in oklab, var(--primary-2, #00aaff) 68%, transparent)
  );
}

.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 1280px) {
  .rag-layout {
    grid-template-columns: 1fr;
  }

  .evidence-grid {
    grid-template-columns: 1fr;
  }

  .summary-strip,
  .two-col,
  .filter-grid {
    grid-template-columns: 1fr;
  }
}
</style>
