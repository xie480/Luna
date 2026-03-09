<template>
  <div class="app-root">

    <!-- ===== 調試 UI ===== -->
    <div
      v-if="showDebugUI"
      ref="uiRef"
      class="debug-ui"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <button @click="toggleTracking">
        {{ trackingEnabled ? "Tracking: ON" : "Tracking: OFF" }}
      </button>
      <button @click="startSetOrigin">
        {{ isSettingOrigin ? "Click canvas..." : "Set Origin" }}
      </button>
      <button @click="clearOrigin">Clear Origin</button>
    </div>

    <!-- ===== 輸入框 ===== -->
    <div
      v-if="showMessageBox"
      class="messageBox"
      ref="messageBoxRef"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
      @contextmenu.stop
      @click.stop
    >
      <div class="fileUploadWrapper">
        <label for="file">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 337 337">
            <circle stroke-width="20" stroke="#6c6c6c" fill="none" r="158.5" cy="168.5" cx="168.5"></circle>
            <path stroke-linecap="round" stroke-width="25" stroke="#6c6c6c" d="M167.759 79V259"></path>
            <path stroke-linecap="round" stroke-width="25" stroke="#6c6c6c" d="M79 167.138H259"></path>
          </svg>
          <span class="tooltip">上傳文件</span>
        </label>
        <input type="file" id="file" name="file" />
      </div>

      <input
        required
        :disabled="isLoading"
        :placeholder="isLoading ? loadingPlaceholder : idlePlaceholder"
        type="text"
        :class="{ loading: isLoading }"
        id="messageInput"
        v-model="input"
        @keydown.enter.prevent="onSend"
      />
      <button id="sendButton" @click="onSend" :disabled="sending">
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 664 663">
          <path fill="none" d="M646.293 331.888L17.7538 17.6187L155.245 331.888M646.293 331.888L17.753 646.157L155.245 331.888M646.293 331.888L318.735 330.228L155.245 331.888"></path>
          <path stroke-linejoin="round" stroke-linecap="round" stroke-width="33.67" stroke="#6c6c6c" d="M646.293 331.888L17.7538 17.6187L155.245 331.888M646.293 331.888L17.753 646.157L155.245 331.888M646.293 331.888L318.735 330.228L155.245 331.888"></path>
        </svg>
      </button>
    </div>

    <!-- ===== 聊天氣泡容器 ===== -->
    <div
      class="bubble-stack"
      :style="{ left: bubbleAnchor.x + 'px', top: bubbleAnchor.y + 'px' }"
    >
      <div
        v-for="bubble in chatBubbles"
        :key="bubble.id"
        class="css-chat-bubble"
        :class="{ leaving: bubble.leaving }"
        :ref="el => registerBubble(el, bubble.id)"
      >
        {{ bubble.text }}
      </div>
    </div>

    <!-- ===== 右鍵菜單 ===== -->
    <div
      v-if="contextMenu.visible"
      ref="contextMenuRef"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
      @click.stop
    >
      <div class="menu-item" @click="toggleDebugUI">
        {{ showDebugUI ? "隱藏追蹤點設置UI" : "顯示追蹤點設置UI" }}
      </div>
      <div class="menu-item" @click="toggleMessageBox">
        {{ showMessageBox ? "隱藏輸入框" : "顯示輸入框" }}
      </div>
      <div class="menu-item" @click="openAppearancePanelAt(contextMenu.x, contextMenu.y)">
        外貌設置
      </div>
      <div class="menu-item" @click="resetModelState">
        重置模型表情
      </div>
      <div class="menu-item" @click="onToggleSystemAudio" style="margin-top:5px;">
        {{ rhythmShowListening ? "關閉系統音頻監聽" : "開啟系統音頻監聽" }}
      </div>
      <div class="menu-item" @click="openHistoryPanelAt(contextMenu.x, contextMenu.y)">
        查看歷史記錄
      </div>
      <div class="menu-item" @click="closeLuna">
        關閉 Luna
      </div>
    </div>

    <!-- ===== 外貌設置面板 ===== -->
    <div
      v-if="appearancePanel.visible"
      class="appearance-panel"
      :style="{ left: appearancePanel.x + 'px', top: appearancePanel.y + 'px' }"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <div class="panel-header drag-handle" @pointerdown="onAppearanceDragStart">
        <span>外貌設置</span>
        <button class="close" @click="closeAppearancePanel">×</button>
      </div>
      <div class="panel-body">
        <div class="appearance-controls">
          <div class="appearance-item" v-for="file in APPEARANCE_FILES" :key="file">
            <label class="checkbox-label">
              <input type="checkbox" v-model="appearanceEnabled[file]" @change="onAppearanceToggleUI(file)" />
              <span class="file-name">{{ displayAppearanceName(file) }}</span>
            </label>
          </div>
        </div>
      </div>
      <div class="panel-footer">
        <button @click="applyAllEnabledUI">應用已啟用項</button>
        <button @click="disableAllUI">全部禁用</button>
      </div>
      <div class="appearance-hint" v-if="appearanceHint">{{ appearanceHint }}</div>
    </div>

    <!-- ===== 歷史記錄面板 ===== -->
    <div
      v-if="historyPanel.visible"
      class="history-panel"
      :style="{ left: historyPanel.x + 'px', top: historyPanel.y + 'px' }"
      ref="historyPanelRef"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
      @click.stop
      @pointerdown.stop
    >
      <div class="header drag-handle" @pointerdown="onHistoryDragStart">
        <div class="header">
          <div class="header-left">
            <button class="nav-btn" @click="changeMonth(-1)" title="上個月">‹</button>
            <button class="nav-btn" @click="changeMonth(1)" title="下個月">›</button>
          </div>
          <div class="header-center">
            <select v-model="historyPanel.selectedYear" @change="onYearChange">
              <option v-for="y in historyPanel.years" :key="y" :value="y">{{ y }}</option>
            </select>
            <select v-model="historyPanel.selectedMonth" @change="onMonthChange">
              <option v-for="m in historyPanel.months" :key="m" :value="m">{{ m }} 月</option>
            </select>
          </div>
          <button class="close" @click="closeHistoryPanel">×</button>
        </div>
      </div>
      <div class="dates-grid">
        <div
          v-for="d in daysInSelectedMonth"
          :key="d"
          class="date-cell"
          :class="{
            available: historyPanel.availableDatesSet.has(d),
            selected: historyPanel.selectedDay === d,
            disabled: !historyPanel.availableDatesSet.has(d)
          }"
          @click="onDateClick(d)"
        >
          {{ d }}
        </div>
      </div>
    </div>

    <!-- ===== 聊天記錄詳情面板 ===== -->
    <div
      v-if="detailVisible"
      class="chat-detail-panel"
      :style="{ left: detailPos.x + 'px', top: detailPos.y + 'px' }"
      @mousedown.stop
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <div class="chat-header drag-handle1" @mousedown="startDrag">
        <div class="h2">與 Luna 的聊天記錄: {{ selectedHistoryDate }}</div>
        <button class="close-btn" @click.stop="detailVisible = false">×</button>
      </div>
      <div class="chat-body" @mousedown.stop>
        <div v-for="(msg, idx) in chatRecords" :key="idx" class="msg-wrapper">
          <div v-if="['STARTUP', 'SHUTDOWN', 'CONTEXT_SUMMARY'].includes(msg.role)" class="msg-notice">
            <span class="notice-content">
              {{ msg.content }}
              <span class="notice-time">{{ msg.time }}</span>
            </span>
          </div>
          <div v-else :class="['message', msg.role === 'USER' ? 'outgoing' : 'incoming']">
            <p class="msg-text">{{ msg.content }}</p>
            <span class="msg-time">{{ msg.time }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== PIXI Canvas ===== -->
    <div ref="wrapperRef" class="interactive-wrapper no-drag">
      <canvas ref="canvasRef" @contextmenu.prevent="onCanvasRightClick"></canvas>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import * as PIXI from "pixi.js";
import { gsap } from "gsap";
import {
  chat as chatApi,
  startup as startupApi,
  shutdown as shutdownApi,
  history as historyApi,
  historyDate as historyDateApi,
} from "../../api/index.js";
import { EMOTION_EXPRESSIONS } from "../../utils/emotion-expressions";
import { Live2DModel } from "pixi-live2d-display/cubism4";

// 拆分出的 composable
import { useBubble } from "../../composables/useBubble.js";
import { useAppearance } from "../../composables/useAppearance.js";
import { useRhythm } from "../../composables/useRhythm.js";

/* ================= DOM refs ================= */
const canvasRef = ref(null);
const wrapperRef = ref(null);
const uiRef = ref(null);
const contextMenuRef = ref(null);
const messageBoxRef = ref(null);
const historyPanelRef = ref(null);

/* ================= 基礎狀態 ================= */
const showDebugUI = ref(false);
const showMessageBox = ref(false);
const trackingEnabled = ref(true);
const isSettingOrigin = ref(false);

// Live2D 核心對象（非響應式）
let app = null;
let container = null;
let model = null;

// 表情緩存（避免重複 fetch）
const expressionCache = new Map();

/* ================= 氣泡（composable） ================= */
const {
  chatBubbles,
  bubbleAnchor,
  registerBubble,
  sendReplyAsBubbles,
} = useBubble(messageBoxRef, showMessageBox);

/* ================= 外貌（composable） ================= */
const {
  APPEARANCE_FILES,
  appearanceEnabled,
  appearanceAppliedMeta,
  appearanceHint,
  showAppearanceHint,
  displayAppearanceName,
  loadAppearanceState,
  saveAppearanceState,
  applyAppearanceFile,
  removeAppearanceFile,
  applyAllEnabled,
  disableAll,
  onAppearanceToggle,
} = useAppearance();

/* ================= 音頻律動（composable） ================= */
const { showSystemAudioListening: rhythmShowListening, toggleSystemAudio, dispose: disposeRhythm } = useRhythm();

/** 獲取當前 coreModel（帶空值保護） */
function getCoreModel() {
  return model?.internalModel?.coreModel ?? null;
}

/* ================= UI 橋接方法（傳入 core） ================= */
async function onAppearanceToggleUI(file) {
  await onAppearanceToggle(file, getCoreModel());
}
async function applyAllEnabledUI() {
  await applyAllEnabled(getCoreModel());
}
async function disableAllUI() {
  await disableAll(getCoreModel());
}
async function onToggleSystemAudio() {
  await toggleSystemAudio(getCoreModel(), trackingEnabled);
}

/* ================= 聊天輸入 ================= */
const input = ref("");
const sending = ref(false);
const lastReply = ref({ loading: false, text: "" });

const isLoading = computed(() => sending.value || lastReply.value.loading);
const idlePlaceholder = "和 Luna 說點什麼吧…";
const loadingPlaceholder = ref("…");

// 加載中省略號動畫計時器
let dotsTimer = null;

watch(isLoading, (loading) => {
  clearInterval(dotsTimer);
  dotsTimer = null;
  if (loading) {
    let count = 1;
    loadingPlaceholder.value = "·";
    dotsTimer = setInterval(() => {
      count = (count % 3) + 1;
      loadingPlaceholder.value = "·".repeat(count);
    }, 500);
  } else {
    loadingPlaceholder.value = idlePlaceholder;
  }
});

/* ================= 響應處理 ================= */

/** 統一規範化後端響應格式 */
function normalizeResponse(res) {
  const data = res?.data ?? res;
  if (typeof data === "string") {
    try { return JSON.parse(data); } catch { return { reply: data }; }
  }
  return data;
}

/**
 * 處理模型回復（表情 + 氣泡）
 * @param {object} res - 已規範化的響應 { emotion, reply }
 */
async function handleModelReply(res) {
  console.log("[Luna] 模型已返回內容", res);
  lastReply.value.loading = false;

  if (!res) return;

  const em = res.emotion || "";
  const replyText = res.reply || res.text || "";

  if (!replyText) return;

  if (em) {
    try { await applyEmotionExpressions(em); } catch {}
  }

  await sendReplyAsBubbles(replyText, { interval: 1000, duration: 5000 });
  lastReply.value.text = replyText;
}

/** 網絡錯誤輕處理 */
function handleNetworkError() {
  lastReply.value.loading = false;
  showAppearanceHint("網絡請求失敗");
}

/* ================= 發送消息 ================= */
async function onSend() {
  if (sending.value) return;
  const text = input.value.trim();
  if (!text) return;

  sending.value = true;
  input.value = "";
  lastReply.value.loading = true;

  try {
    const res = await chatApi({ userInput: text });
    await handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 發送失敗", e);
    handleNetworkError();
  } finally {
    sending.value = false;
    await nextTick();
  }
}

/* ================= 啟動 / 關閉 ================= */
async function callStartup() {
  lastReply.value.loading = true;
  try {
    const res = await startupApi();
    await handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 啟動失敗", e);
    handleNetworkError();
  }
}

async function callShutdown() {
  lastReply.value.loading = true;
  try {
    // 修復：統一使用 normalizeResponse 處理響應
    const res = await shutdownApi();
    await handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 關閉失敗", e);
  } finally {
    lastReply.value.loading = false;
  }
}

/* ================= 歷史記錄面板 ================= */
const historyPanel = ref({
  visible: false,
  x: 100,
  y: 100,
  years: [],
  months: [],
  selectedYear: null,
  selectedMonth: null,
  selectedDay: null, // 修復：直接在初始化對象內聲明
  availableDates: [],
  availableDatesSet: new Set(),
  loading: false,
});

// 初始化年份和月份選項
;(function initHistoryDefaults() {
  const now = new Date();
  const cy = now.getFullYear();
  const years = [];
  for (let y = cy; y >= cy - 6; y--) years.push(y);
  historyPanel.value.years = years;
  historyPanel.value.months = Array.from({ length: 12 }, (_, i) => i + 1);
})();

const daysInSelectedMonth = computed(() => {
  const y = historyPanel.value.selectedYear;
  const m = historyPanel.value.selectedMonth;
  if (!y || !m) return [];
  return Array.from({ length: new Date(y, m, 0).getDate() }, (_, i) => i + 1);
});

/** 拉取指定年月的可用日期列表 */
async function fetchHistoryForMonth(year, month) {
  if (!year || !month) return;
  historyPanel.value.loading = true;
  historyPanel.value.selectedDay = null;
  try {
    const yearMonth = `${year}:${String(month).padStart(2, "0")}`;
    const arr = await historyDateApi(yearMonth);
    const days = (arr || []).map(Number).filter((d) => !isNaN(d));
    historyPanel.value.availableDates = [...days];
    historyPanel.value.availableDatesSet = new Set(days);
  } catch (e) {
    console.error("[History] fetchHistoryForMonth 失敗", e);
  } finally {
    historyPanel.value.loading = false;
  }
}

/** 切換月份（支持跨年） */
async function changeMonth(delta) {
  const now = new Date();
  if (!historyPanel.value.selectedYear) historyPanel.value.selectedYear = now.getFullYear();
  if (!historyPanel.value.selectedMonth) historyPanel.value.selectedMonth = 1;

  let y = historyPanel.value.selectedYear;
  let m = historyPanel.value.selectedMonth + delta;

  if (m < 1) { m = 12; y--; }
  else if (m > 12) { m = 1; y++; }

  historyPanel.value.selectedYear = y;
  historyPanel.value.selectedMonth = m;

  if (!historyPanel.value.years.includes(y)) {
    historyPanel.value.years.push(y);
    historyPanel.value.years.sort((a, b) => b - a);
  }

  await fetchHistoryForMonth(y, m);
}

function onYearChange() {
  fetchHistoryForMonth(historyPanel.value.selectedYear, historyPanel.value.selectedMonth);
}
function onMonthChange() {
  fetchHistoryForMonth(historyPanel.value.selectedYear, historyPanel.value.selectedMonth);
}

function openHistoryPanelAt(x, y) {
  historyPanel.value.visible = true;
  historyPanel.value.x = x;
  historyPanel.value.y = y;
  const now = new Date();
  historyPanel.value.selectedYear = historyPanel.value.selectedYear || now.getFullYear();
  historyPanel.value.selectedMonth = historyPanel.value.selectedMonth || now.getMonth() + 1;
  fetchHistoryForMonth(historyPanel.value.selectedYear, historyPanel.value.selectedMonth);
}

function closeHistoryPanel() {
  historyPanel.value.visible = false;
}

/* 歷史面板拖拽 */
let draggingHistoryPanel = false;
let historyDragStart = { x: 0, y: 0 };
let historyPanelStart = { x: 0, y: 0 };

function onHistoryDragStart(e) {
  if (e.button !== 0) return;
  draggingHistoryPanel = true;
  historyDragStart = { x: e.clientX, y: e.clientY };
  historyPanelStart = { x: historyPanel.value.x, y: historyPanel.value.y };
  ```
  document.addEventListener("pointermove", onHistoryDragMove);
  document.addEventListener("pointerup", onHistoryDragEnd);
}

function onHistoryDragMove(e) {
  if (!draggingHistoryPanel) return;
  const dx = e.clientX - historyDragStart.x;
  const dy = e.clientY - historyDragStart.y;
  const maxX = window.innerWidth - 320;
  const maxY = window.innerHeight - 300;
  historyPanel.value.x = Math.min(maxX, Math.max(0, historyPanelStart.x + dx));
  historyPanel.value.y = Math.min(maxY, Math.max(0, historyPanelStart.y + dy));
}

function onHistoryDragEnd() {
  draggingHistoryPanel = false;
  document.removeEventListener("pointermove", onHistoryDragMove);
  document.removeEventListener("pointerup", onHistoryDragEnd);
}

/* ================= 聊天記錄詳情 ================= */
const detailVisible = ref(false);
const chatRecords = ref([]);
const selectedHistoryDate = ref("");
const detailPos = ref({ x: window.innerWidth / 2 - 175, y: 100 });

let isDragging = false;
let startX = 0;
let startY = 0;

function startDrag(e) {
  isDragging = true;
  startX = e.clientX - detailPos.value.x;
  startY = e.clientY - detailPos.value.y;
  window.addEventListener("mousemove", onDragging);
  window.addEventListener("mouseup", stopDrag);
}

function onDragging(e) {
  if (!isDragging) return;
  detailPos.value.x = e.clientX - startX;
  detailPos.value.y = e.clientY - startY;
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener("mousemove", onDragging);
  window.removeEventListener("mouseup", stopDrag);
}

/** 點擊日期格，拉取當天聊天記錄 */
async function onDateClick(d) {
  if (!historyPanel.value.availableDatesSet.has(d)) return;

  const y = historyPanel.value.selectedYear;
  const m = historyPanel.value.selectedMonth;
  historyPanel.value.selectedDay = d;
  const dateStr = `${y}:${String(m).padStart(2, "0")}:${String(d).padStart(2, "0")}`;
  selectedHistoryDate.value = dateStr;

  try {
    const res = await historyApi(dateStr);
    const rawList = res?.data ?? res;

    // 修復：加入類型校驗，防止 item 不是字符串時報錯
    chatRecords.value = (Array.isArray(rawList) ? rawList : [])
      .filter((item) => typeof item === "string")
      .map((item) => {
        const parts = item.split(":");
        const time = parts.slice(-3).join(":");
        const role = parts[0];
        const content = parts.slice(1, -3).join(":");
        return { role, content, time };
      });

    // 修復：移除重複賦值，只賦值一次
    detailVisible.value = true;
  } catch (e) {
    console.error("[History] 獲取聊天詳情失敗:", e);
  }
}

/* ================= 右鍵菜單 ================= */
function onRightClick(e) {
  if (uiRef.value?.contains(e.target)) return;
  if (messageBoxRef.value?.contains(e.target)) return;
  showContextMenu(e.clientX, e.clientY);
}

function onCanvasRightClick(e) {
  showContextMenu(e.clientX, e.clientY);
  e.preventDefault();
}

function showContextMenu(x, y) {
  contextMenu.value = { visible: true, x, y };
  nextTick(() => {
    if (!contextMenuRef.value) return;
    const { width, height } = contextMenuRef.value.getBoundingClientRect();
    if (x + width > window.innerWidth) contextMenu.value.x = window.innerWidth - width - 10;
    if (y + height > window.innerHeight) contextMenu.value.y = window.innerHeight - height - 10;
  });
}

const contextMenu = ref({ visible: false, x: 0, y: 0 });

function handleClickOutside(e) {
  if (contextMenu.value.visible && contextMenuRef.value && !contextMenuRef.value.contains(e.target)) {
    contextMenu.value.visible = false;
  }
  if (historyPanel.value.visible && historyPanelRef.value && !historyPanelRef.value.contains(e.target)) {
    historyPanel.value.visible = false;
  }
}

/* ================= 外貌面板 ================= */
const appearancePanel = ref({ visible: false, x: 100, y: 100 });

let draggingAppearance = false;
let dragStart = { x: 0, y: 0 };
let panelStart = { x: 0, y: 0 };

function onAppearanceDragStart(e) {
  if (e.button !== 0) return;
  draggingAppearance = true;
  dragStart = { x: e.clientX, y: e.clientY };
  panelStart = { x: appearancePanel.value.x, y: appearancePanel.value.y };
  document.addEventListener("pointermove", onAppearanceDragMove);
  document.addEventListener("pointerup", onAppearanceDragEnd);
}

function onAppearanceDragMove(e) {
  if (!draggingAppearance) return;
  const maxX = window.innerWidth - 420;
  const maxY = window.innerHeight - 360;
  appearancePanel.value.x = Math.min(maxX, Math.max(0, panelStart.x + e.clientX - dragStart.x));
  appearancePanel.value.y = Math.min(maxY, Math.max(0, panelStart.y + e.clientY - dragStart.y));
}

function onAppearanceDragEnd() {
  draggingAppearance = false;
  document.removeEventListener("pointermove", onAppearanceDragMove);
  document.removeEventListener("pointerup", onAppearanceDragEnd);
  updatePetState();
}

function openAppearancePanelAt(x, y) {
  const panelWidth = 420;
  const panelHeight = 360;
  appearancePanel.value.x = x + panelWidth > window.innerWidth ? window.innerWidth - panelWidth - 10 : x;
  appearancePanel.value.y = y + panelHeight > window.innerHeight ? window.innerHeight - panelHeight - 10 : y;
  appearancePanel.value.visible = true;
  overUI = true;
  window.pet?.enter();
  contextMenu.value.visible = false;
}

function closeAppearancePanel() {
  appearancePanel.value.visible = false;
  overUI = false;
  updatePetState();
}

/* ================= 穿透管理 ================= */
let overModel = false;
let overUI = false;

function updatePetState() {
  if (overModel || overUI) window.pet?.enter();
  else window.pet?.leave();
}

function uiEnter() { overUI = true; updatePetState(); }
function uiLeave() { overUI = false; updatePetState(); }

watch(showMessageBox, (v) => { if (v) window.pet?.enter(); else updatePetState(); });
watch(showDebugUI, (v) => { if (v) window.pet?.enter(); else updatePetState(); });

/* ================= 拖拽模型 ================= */
let dragging = false;
let lastPos = { x: 0, y: 0 };

function onPointerDown(e) {
  const oe = e.data?.originalEvent;
  if (!oe || oe.button !== 0) return;
  const gp = e.data.global;
  if (!isPointInsideModel(gp)) return;
  dragging = true;
  lastPos = { x: gp.x, y: gp.y };
}

function onPointerMove(e) {
  if (!dragging) return;
  const dx = e.data.global.x - lastPos.x;
  const dy = e.data.global.y - lastPos.y;
  lastPos = { ...e.data.global };
  container.x += dx;
  container.y += dy;
}

function onPointerUp() { dragging = false; }

function isPointInsideModel(globalPoint) {
  if (!model) return false;
  return model.getBounds().contains(globalPoint.x, globalPoint.y);
}

/* ================= 視線追蹤 ================= */
const PARAM_CONFIG = {
  HEAD_X: { param: "ParamAngleX", range: [-30, 30] },
  HEAD_Y: { param: "ParamAngleY", range: [-30, 30] },
  EYE_X:  { param: "ParamEyeBallX", range: [-1, 1] },
  EYE_Y:  { param: "ParamEyeBallY", range: [-1, 1] },
  BREATH: { param: "ParamBreath", range: [0, 1] },
};

const LOOK_ORIGIN_KEY = "live2d:look-origin";
let lookOriginLocal = null;

function applyLookAt(dx, dy) {
  const core = getCoreModel();
  if (!core) return;
  const nx = Math.max(-1, Math.min(1, dx / (app.renderer.width / 2)));
  const ny = -Math.max(-1, Math.min(1, dy / (app.renderer.height / 2)));
  const mapRange = (v, [min, max]) => min + ((v + 1) / 2) * (max - min);
  try {
    core.setParameterValueById(PARAM_CONFIG.EYE_X.param, mapRange(nx, PARAM_CONFIG.EYE_X.range));
    core.setParameterValueById(PARAM_CONFIG.EYE_Y.param, mapRange(ny, PARAM_CONFIG.EYE_Y.range));
    core.setParameterValueById(PARAM_CONFIG.HEAD_X.param, mapRange(nx, PARAM_CONFIG.HEAD_X.range));
    core.setParameterValueById(PARAM_CONFIG.HEAD_Y.param, mapRange(ny, PARAM_CONFIG.HEAD_Y.range));
  } catch {}
}

function onGlobalPointerMove(ev) {
  if (!trackingEnabled.value || !lookOriginLocal || !model) return;
  const rect = canvasRef.value.getBoundingClientRect();
  const world = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  const local = container.toLocal(world, app.stage);
  applyLookAt(local.x - lookOriginLocal.x, local.y - lookOriginLocal.y);
}

function onCanvasClick(ev) {
  if (!isSettingOrigin.value) return;
  const rect = canvasRef.value.getBoundingClientRect();
  const world = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  lookOriginLocal = container.toLocal(world, app.stage);
  saveOrigin();
  isSettingOrigin.value = false;
}

function saveOrigin() {
  if (!lookOriginLocal) return;
  localStorage.setItem(LOOK_ORIGIN_KEY, JSON.stringify({ x: lookOriginLocal.x, y: lookOriginLocal.y }));
}

function loadOrigin() {
  const raw = localStorage.getItem(LOOK_ORIGIN_KEY);
  if (raw) lookOriginLocal = JSON.parse(raw);
}

function clearOrigin() {
  lookOriginLocal = null;
  localStorage.removeItem(LOOK_ORIGIN_KEY);
}

/* ================= 滾輪縮放 ================= */
function onWheel(ev) {
  const rect = canvasRef.value.getBoundingClientRect();
  const globalPoint = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  if (!isPointInsideModel(globalPoint)) return;
  ev.preventDefault();
  const factor = ev.deltaY > 0 ? 0.95 : 1.05;
  const newScale = Math.min(10, Math.max(0.05, (container.scale.x || 1) * factor));
  const localPoint = container.toLocal(globalPoint, app.stage);
  container.scale.set(newScale);
  const newGlobal = container.toGlobal(localPoint);
  container.position.x += globalPoint.x - newGlobal.x;
  container.position.y += globalPoint.y - newGlobal.y;
}

/* ================= 調試 UI ================= */
function toggleTracking() {
  trackingEnabled.value = !trackingEnabled.value;
  if (!trackingEnabled.value) {
    const core = getCoreModel();
    if (core) {
      [PARAM_CONFIG.EYE_X.param, PARAM_CONFIG.EYE_Y.param,
       PARAM_CONFIG.HEAD_X.param, PARAM_CONFIG.HEAD_Y.param]
        .forEach((p) => { try { core.setParameterValueById(p, 0); } catch {} });
    }
  }
}

function startSetOrigin() {
  isSettingOrigin.value = true;
  window.pet?.enter();
}

function toggleDebugUI() {
  showDebugUI.value = !showDebugUI.value;
  contextMenu.value.visible = false;
  if (!showDebugUI.value) { overUI = false; updatePetState(); }
  else window.pet?.enter();
}

function toggleMessageBox() {
  showMessageBox.value = !showMessageBox.value;
  contextMenu.value.visible = false;
  if (!showMessageBox.value) { overUI = false; updatePetState(); }
}

/* ================= 呼吸動畫 ================= */
let breathTickerFn = null;

function startBreath() {
  const breathStart = performance.now() / 1000;
  breathTickerFn = () => {
    const core = getCoreModel();
    if (!core) return;
    const t = performance.now() / 1000 - breathStart;
    const val = 0.5 + Math.sin(t * 0.9 * Math.PI * 2) * 0.15;
    try { core.setParameterValueById(PARAM_CONFIG.BREATH.param, val); } catch {}
  };
  app.ticker.add(breathTickerFn);
}

function stopBreath() {
  if (breathTickerFn && app?.ticker) {
    app.ticker.remove(breathTickerFn);
    breathTickerFn = null;
  }
}

/* ================= 表情合成 ================= */

/** 預設初始表情，可按需替換為動態配置 */
const INITIAL_EMOTION = "Solemn";

// 記錄當前表情修改的參數原始值，用於重置
let currentEmotionMeta = {};

async function resetToSolemn() {
  const core = getCoreModel();
  if (!core) return;
  const keys = Object.keys(currentEmotionMeta);
  if (!keys.length) return;
  for (const id of keys) {
    try {
      core.setParameterValueById(id, typeof currentEmotionMeta[id] === "number" ? currentEmotionMeta[id] : 0);
    } catch (e) {
      console.warn("[Luna] resetToSolemn 恢復失敗:", id, e);
    }
  }
  currentEmotionMeta = {};
  await new Promise((r) => requestAnimationFrame(r));
}

function tweenParameters(core, targetValues, duration = 200) {
  return new Promise((resolve) => {
    const startTime = performance.now();
    const fromValues = {};
    for (const id in targetValues) {
      fromValues[id] = core.getParameterValueById(id) ?? 0;
    }
    function step(now) {
      const t = Math.min((now - startTime) / duration, 1);
      const k = t * t * (3 - 2 * t); // smoothstep
      for (const id in targetValues) {
        core.setParameterValueById(id, fromValues[id] + (targetValues[id] - fromValues[id]) * k);
      }
      if (t < 1) requestAnimationFrame(step);
      else resolve();
    }
    requestAnimationFrame(step);
  });
}

async function applyEmotionExpressions(emotion) {
  const core = getCoreModel();
  if (!core) return;

  await resetToSolemn();
  await new Promise((r) => requestAnimationFrame(r));

  const names = EMOTION_EXPRESSIONS?.[emotion] || [];
  if (!names.length) return;

  const targetValues = {};
  const thisApplyPrev = {};

  for (const cnName of names) {
    const expJson = expressionCache.get(cnName);
    if (!expJson) continue;
    (expJson.Parameters || []).forEach(({ Id, Value, Blend }) => {
      const base = targetValues[Id] ?? core.getParameterValueById(Id) ?? 0;
      if (!(Id in thisApplyPrev)) thisApplyPrev[Id] = base;
      if (Blend === "Add") targetValues[Id] = base + Value;
      else if (Blend === "Multiply") targetValues[Id] = base * Value;
      else targetValues[Id] = Value;
    });
  }

  await tweenParameters(core, targetValues, 180);
  currentEmotionMeta = thisApplyPrev;

  // 重新應用外貌，防止表情覆蓋外貌參數
  await applyAllEnabled(getCoreModel());
}

/* ================= 預加載表情文件 ================= */
async function preloadExpressions() {
  const allFiles = [
    "眼-生氣", "臉紅2隱藏", "臉黑", "眼-哭哭", "眼-淚眼汪汪",
    "眼-眩暈流汗", "臉紅", "眼-平靜死魚眼", "嘴-平靜v形（不可張開",
    "眼-星星眼", "臉紅-痴漢嘴（兼容吐舌", "眼-愛心眼",
  ];
  console.log(`[Live2D] 開始預加載表情，共 ${allFiles.length} 個`);
  await Promise.all(
    allFiles.map(async (name) => {
      try {
        const res = await fetch(`/models/luna/${encodeURIComponent(name)}.exp3.json`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        expressionCache.set(name, await res.json());
        console.log(`[Live2D] 加載成功: ${name}`);
      } catch (e) {
        console.error(`[Live2D] 加載失敗: ${name}`, e);
      }
    })
  );
  console.log("[Live2D] 表情預加載完成");
}

/* ================= 重置模型狀態 ================= */
async function resetModelState() {
  const core = getCoreModel();
  if (!core) return;
  for (const f in appearanceAppliedMeta) {
    removeAppearanceFile(f, core);
  }
  await resetToSolemn();
  await applyAllEnabled(core);
  showAppearanceHint("模型表情已重置");
}

/* ================= 關閉 Luna ================= */
function closeLuna() {
  try {
    stopBreath();
    app?.destroy(true);
    window.pet?.leave?.();
  } catch (e) {
    console.warn("[Luna] 關閉出錯", e);
  }
  if (wrapperRef.value) wrapperRef.value.innerHTML = "";
  showMessageBox.value = false;
  contextMenu.value.visible = false;
}

/* ================= 等待模型就緒 ================= */
function waitForModelReady(timeout = 5000) {
  return new Promise((resolve) => {
    const start = performance.now();
    (function poll() {
      if (model?.internalModel?.coreModel) return resolve(true);
      if (performance.now() - start > timeout) return resolve(false);
      setTimeout(poll, 120);
    })();
  });
}

/* ================= 生命周期 ================= */
onMounted(async () => {
  window.PIXI = PIXI;

  app = new PIXI.Application({
    view: canvasRef.value,
    backgroundAlpha: 0,
    resizeTo: wrapperRef.value,
  });

  container = new PIXI.Container();
  app.stage.addChild(container);

  model = await Live2DModel.from("/models/luna/jk盐.model3.json", {
    autoInteract: false,
    ticker: PIXI.Ticker.shared,
  });

  model.scale.set(0.1);
  model.anchor.set(0.5, 1);
  model.x = app.renderer.width / 2;
  model.y = app.renderer.height;
  model.interactive = true;
  model.cursor = "pointer";

  model
    .on("pointerdown", onPointerDown)
    .on("pointermove", onPointerMove)
    .on("pointerup", onPointerUp)
    .on("pointerupoutside", onPointerUp);

  model.on("pointerover", () => { overModel = true; updatePetState(); });
  model.on("pointerout",  () => { overModel = false; updatePetState(); });
  model.on("rightclick",  (e) => {
    const rect = canvasRef.value.getBoundingClientRect();
    showContextMenu(rect.left + e.data.global.x, rect.top + e.data.global.y);
  });

  container.addChild(model);
  loadOrigin();

  wrapperRef.value.addEventListener("pointermove", onGlobalPointerMove);
  wrapperRef.value.addEventListener("pointerdown", onCanvasClick);
  wrapperRef.value.addEventListener("wheel", onWheel, { passive: false });
  document.addEventListener("click", handleClickOutside);
  document.addEventListener("contextmenu", onRightClick);

  await preloadExpressions();
  startBreath();

  loadAppearanceState();
  await waitForModelReady(5000);
  await nextTick();
  await applyAllEnabled(getCoreModel());

  applyEmotionExpressions(INITIAL_EMOTION);
  callStartup();
});

onBeforeUnmount(() => {
  // 清理省略號計時器（修復：原先未在卸載時清理）
  clearInterval(dotsTimer);
  dotsTimer = null;

  stopBreath();

  // 釋放音頻律動資源
  disposeRhythm(getCoreModel(), trackingEnabled);

  app?.destroy(true);
  callShutdown();

  document.removeEventListener("click", handleClickOutside);
  document.removeEventListener("contextmenu", onRightClick);
});
</script>

<style scoped>
.css-chat-bubble {
  max-width: 280px;
  padding: 10px 14px;
  width: fit-content;
  border: 1px solid rgba(0, 0, 0, 0.15);
  background: linear-gradient(to bottom, rgba(255,255,255,0.95), rgba(245,245,245,0.9));
  border-radius: 14px;
  color: #333;
  font-size: 14px;
  line-height: 1.5;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  word-break: break-word;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  animation: bubbleIn 0.22s ease-out both;
}

@keyframes bubbleIn {
  from { opacity: 0; transform: scale(0.85); }
  to   { opacity: 1; transform: scale(1); }
}

@keyframes bubbleOut {
  from { opacity: 1; transform: scale(1); }
  to   { opacity: 0; transform: scale(0.9); }
}

.css-chat-bubble.leaving {
  animation: bubbleOut 0.18s ease-in forwards;
}

.bubble-stack {
  position: fixed;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  transform: translate(-50%, -100%);
  pointer-events: none;
  z-index: 1002;
}

/* ================= 輸入框 ================= */
.messageBox {
  position: absolute;
  top: 800px;
  left: 490px;
  width: 700px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #2d2d2d;
  padding: 0 15px;
  border-radius: 10px;
  border: 1px solid rgb(63, 63, 63);
  z-index: 1001;
  pointer-events: auto;
}

.messageBox * { pointer-events: auto; }

.messageBox:focus-within {
  border: 1px solid rgb(110, 110, 110);
}

.fileUploadWrapper {
  position: relative;
  width: fit-content;
  height: 110%;
  display: flex;
  align-items: center;
  justify-content: center;
}

#file { display: none; }

.fileUploadWrapper label {
  cursor: pointer;
  width: fit-content;
  height: fit-content;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

.fileUploadWrapper label svg { height: 18px; }
.fileUploadWrapper label svg path,
.fileUploadWrapper label svg circle { transition: all 0.3s; }
.fileUploadWrapper label:hover svg path { stroke: #fff; }
.fileUploadWrapper label:hover svg circle { stroke: #fff; fill: #3c3c3c; }
.fileUploadWrapper label:hover .tooltip { display: block; opacity: 1; }

.tooltip {
  position: absolute;
  top: -40px;
  display: none;
  opacity: 0;
  color: white;
  font-size: 10px;
  text-wrap: nowrap;
  background-color: #000;
  padding: 6px 10px;
  border-radius: 5px;
  border: 1px solid #3c3c3c;
  box-shadow: 0px 5px 10px rgba(0,0,0,0.596);
  transition: all 0.3s;
}

#messageInput {
  width: 650px;
  height: 100%;
  background-color: transparent;
  outline: none;
  border: none;
  padding-left: 10px;
  color: white;
}

#messageInput:focus ~ #sendButton svg path,
#messageInput:valid ~ #sendButton svg path {
  fill: #3c3c3c;
  stroke: white;
}

#sendButton {
  width: fit-content;
  height: 100%;
  background-color: transparent;
  outline: none;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.3s;
}

#sendButton svg { height: 18px; transition: all 0.3s; }
#sendButton svg path { transition: all 0.3s; }
#sendButton:hover svg path { fill: #3c3c3c; stroke: white; }

#messageInput.loading::placeholder {
  animation: placeholderBlink 1.2s infinite;
}

@keyframes placeholderBlink {
  0%   { opacity: 0.3; }
  50%  { opacity: 1; }
  100% { opacity: 0.3; }
}

/* ================= 通用佈局 ================= */
.app-root {
  width: 100vw;
  height: 100vh;
  position: relative;
  overflow: hidden;
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
}

.interactive-wrapper {
  width: 100%;
  height: 100%;
  position: relative;
  z-index: 1;
}

.no-drag { -webkit-app-region: no-drag; }

/* ================= 調試 UI ================= */
.debug-ui {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 1002;
  display: flex;
  gap: 8px;
  background: rgba(255,255,255,0.92);
  padding: 10px;
  border-radius: 10px;
  box-shadow: 0 4px 10px rgba(0,0,0,0.12);
  pointer-events: auto;
}

.debug-ui button {
  background: #2d2d2d;
  color: #fff;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s ease;
}

.debug-ui button:hover { background: #444; }

/* ================= 右鍵菜單 ================= */
.context-menu {
  position: fixed;
  z-index: 1003;
  background: #1f1f1f;
  border-radius: 8px;
  padding: 6px 0;
  color: #ececec;
  min-width: 160px;
  box-shadow: 0 8px 18px rgba(0,0,0,0.25);
  font-size: 14px;
  pointer-events: auto;
  user-select: none;
}

.context-menu .menu-item {
  padding: 10px 16px;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.18s ease, padding-left 0.18s ease;
}

.context-menu .menu-item:hover {
  background: #303030;
  padding-left: 20px;
  color: #fff;
}

.context-menu .menu-item:not(:last-child) {
  border-bottom: 1px solid rgba(255,255,255,0.12);
}

/* ================= 外貌面板 ================= */
.appearance-panel {
  position: fixed;
  z-index: 1004;
  width: 420px;
  height: 360px;
  background: #111;
  color: #efefef;
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.6);
  padding: 10px;
  display: flex;
  flex-direction: column;
  pointer-events: auto;
}

.appearance-panel .panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 8px;
  border-bottom: 1px solid rgba(255,255,255,0.05);
  font-weight: 600;
  cursor: move;
  user-select: none;
}

.appearance-panel .panel-header .close {
  background: transparent;
  border: none;
  color: #ddd;
  font-size: 18px;
  cursor: pointer;
}

.appearance-panel .panel-body {
  padding: 8px;
  overflow: auto;
  flex: 1;
}

.appearance-controls { display: flex; flex-direction: column; gap: 6px; }
.appearance-item { display: flex; align-items: center; }

.checkbox-label {
  display: flex;
  gap: 8px;
  align-items: center;
  cursor: pointer;
  user-select: none;
}

.checkbox-label input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: #4caf50;
}

.file-name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 340px;
}

.appearance-panel .panel-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 1px solid rgba(255,255,255,0.03);
}

.appearance-panel .panel-footer button {
  background: #222;
  border: 1px solid rgba(255,255,255,0.06);
  color: #fff;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.appearance-panel .panel-footer button:hover { opacity: 0.9; }

.appearance-hint {
  margin-top: 6px;
  font-size: 11px;
  color: rgba(255,255,255,0.55);
  text-align: right;
  padding-right: 4px;
  user-select: none;
  pointer-events: none;
  animation: appearanceHintFade 0.15s ease-out;
}

@keyframes appearanceHintFade {
  from { opacity: 0; transform: translateY(-2px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* ================= 歷史面板 ================= */
.history-panel {
  position: absolute;
  width: 320px;
  background: #2d2d2d;
  border: 1px solid #444;
  border-radius: 10px;
  padding: 12px;
  z-index: 3000;
  color: #fff;
  pointer-events: auto;
}

.history-panel .header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.header-left { display: flex; gap: 6px; align-items: center; }
.header-center { display: flex; gap: 8px; align-items: center; margin-left: 10px; }

.nav-btn {
  background: transparent;
  border: 1px solid 1px rgba(255,255,255,0.06);
  color: #fff;
  border-radius: 6px;
  width: 28px;
  height: 28px;
  cursor: pointer;
}

.history-panel select {
  background: rgba(0,0,0,0.12);
  color: #fff;
  border: 1px solid rgba(255,255,255,0.04);
  padding: 4px 8px;
  border-radius: 6px;
}

.history-panel select option {
  background-color: #3a3a3a;
  color: #fff;
}

.drag-handle {
  cursor: move;
  padding: 4px 8px;
  border-radius: 6px 6px 0 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.dates-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  padding: 6px;
  pointer-events: auto;
}

.date-cell {
  width: 36px;
  height: 36px;
  line-height: 36px;
  text-align: center;
  border-radius: 6px;
  background-color: #3a3a3a;
  cursor: pointer;
  transition: all 0.2s;
  pointer-events: auto;
}

.date-cell.available {
  background: linear-gradient(180deg, rgba(78,162,255,0.12), rgba(78,162,255,0.06));
  color: #dff3ff;
  font-weight: 600;
}

.date-cell.available:hover { background: #4a90e2; }

.date-cell.selected {
  background: #4a90e2;
  font-weight: bold;
  color: #fff;
  box-shadow: 0 6px 18px rgba(78,162,255,0.18);
}

.date-cell.disabled {
  background: #1f1f1f;
  color: #666;
  cursor: not-allowed;
}

/* ================= 聊天記錄詳情面板 ================= */
.chat-detail-panel {
  position: fixed;
  width: 360px;
  height: 500px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0,0,0,0.4);
  display: flex;
  flex-direction: column;
  z-index: 9999;
  overflow: hidden;
  user-select: none;
  border: 1px solid rgba(0,0,0,0.1);
}

.drag-handle1 {
  cursor: move;
  background: #f8f9fa;
  border-bottom: 1px solid #eee;
  padding: 10px 15px;
}

.chat-header {
  padding: 12px;
  background-color: #f8f9fa;
  border-bottom: 1px solid #eee;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chat-header .h2 { font-size: 14px; color: #333; font-weight: bold; }

.close-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: #999;
}

.chat-body {
  flex: 1;
  padding: 15px;
  overflow-y: auto;
  background-color: #fdfdfd;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: auto;
}

.msg-wrapper { display: flex; flex-direction: column; }

.msg-notice {
  align-self: center;
  margin: 8px 0;
  text-align: center;
  max-width: 90%;
}

.notice-content {
  font-size: 11px;
  color: #888;
  background: #f0f0f0;
  padding: 3px 10px;
  border-radius: 10px;
}

.notice-time { margin-left: 5px; opacity: 0.7; font-size: 10px; }

.message {
  padding: 0px 14px;
  border-radius: 12px;
  max-width: 80%;
  font-size: 14px;
  line-height: 1.5;
  display: flex;
  flex-direction: column;
  word-wrap: break-word;
  margin-bottom: 4px;
}

.incoming {
  align-self: flex-start;
  background-color: #f0f0f0;
  color: #333;
  border-bottom-left-radius: 2px;
  align-items: flex-start;
}

.incoming .msg-time {
  text-align: left;
  margin-top: -10px;
  font-size: 10px;
  opacity: 0.5;
}

.outgoing {
  align-self: flex-end;
  background-color: #4285f4;
  color: #fff;
  border-bottom-right-radius: 2px;
  align-items: flex-end;
}

.outgoing .msg-text { margin-left: 5px; }

.outgoing .msg-time {
  text-align: right;
  margin-top: -10px;
  font-size: 10px;
  opacity: 0.8;
}

@keyframes chatAnimation {
  0%   { opacity: 0; transform: translateY(5px); }
  100% { opacity: 1; transform: translateY(0); }
}
</style>
