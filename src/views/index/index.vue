<template>
  <div class="app-root">

    <!-- ===== 調試 UI ===== -->
    <div
      v-if="showDebugUI"
      ref="uiRef"
      class="debug-ui glass-panel"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <button class="cute-btn" @click="toggleTracking">
        <span class="btn-icon">{{ trackingEnabled ? "👁" : "😑" }}</span>
        {{ trackingEnabled ? "Tracking ON" : "Tracking OFF" }}
      </button>
      <button class="cute-btn" @click="startSetOrigin">
        <span class="btn-icon">🎯</span>
        {{ isSettingOrigin ? "點擊畫布..." : "Set Origin" }}
      </button>
      <button class="cute-btn" @click="clearOrigin">
        <span class="btn-icon">✕</span>
        Clear Origin
      </button>
    </div>

    <!-- ===== 輸入框 ===== -->
    <Transition name="msgbox">
      <div
        v-if="showMessageBox"
        class="messageBox"
        :class="[`emotion-${currentEmotion}`, { 'is-loading': isLoading }]"
        ref="messageBoxRef"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
        @contextmenu.stop
        @click.stop
      >
        <!-- 情緒指示條 -->
        <div class="emotion-bar"></div>

        <div class="fileUploadWrapper">
          <label for="file" class="upload-label">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 337 337">
              <circle stroke-width="20" stroke="currentColor" fill="none" r="158.5" cy="168.5" cx="168.5"></circle>
              <path stroke-linecap="round" stroke-width="25" stroke="currentColor" d="M167.759 79V259"></path>
              <path stroke-linecap="round" stroke-width="25" stroke="currentColor" d="M79 167.138H259"></path>
            </svg>
            <span class="tooltip">上傳文件</span>
          </label>
          <input type="file" id="file" name="file" />
        </div>

        <!-- 加載動畫（跳動點） -->
        <div v-if="isLoading" class="loading-dots">
          <span></span><span></span><span></span>
        </div>

        <input
          v-else
          required
          :placeholder="idlePlaceholder"
          type="text"
          id="messageInput"
          v-model="input"
          @keydown.enter.prevent="onSend"
          @focus="inputFocused = true"
          @blur="inputFocused = false"
        />

        <button
          id="sendButton"
          class="send-btn"
          @click="onSend"
          :disabled="sending || isLoading"
          :class="{ active: input.trim().length > 0 }"
        >
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 664 663">
            <path stroke-linejoin="round" stroke-linecap="round" stroke-width="33.67" stroke="currentColor" d="M646.293 331.888L17.7538 17.6187L155.245 331.888M646.293 331.888L17.753 646.157L155.245 331.888M646.293 331.888L318.735 330.228L155.245 331.888"></path>
          </svg>
        </button>
      </div>
    </Transition>

    <!-- ===== 聊天氣泡容器 ===== -->
    <div
      class="bubble-stack"
      :style="{ left: bubbleAnchor.x + 'px', top: bubbleAnchor.y + 'px' }"
    >
      <TransitionGroup name="bubble">
        <div
          v-for="bubble in chatBubbles"
          :key="bubble.id"
          class="css-chat-bubble"
          :class="[`bubble-emotion-${bubble.emotion || 'default'}`, { leaving: bubble.leaving }]"
          :ref="el => registerBubble(el, bubble.id)"
        >
          <span class="bubble-text">{{ bubble.text }}</span>
        </div>
      </TransitionGroup>
    </div>

    <!-- ===== 右鍵菜單 ===== -->
    <Transition name="menu">
      <div
        v-if="contextMenu.visible"
        ref="contextMenuRef"
        class="context-menu glass-panel"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
        @click.stop
      >
        <div class="menu-item" @click="toggleDebugUI">
          <span class="menu-icon">{{ showDebugUI ? "🙈" : "🐱" }}</span>
          {{ showDebugUI ? "隱藏調試UI" : "顯示調試UI" }}
        </div>
        <div class="menu-item" @click="toggleMessageBox">
          <span class="menu-icon">{{ showMessageBox ? "💬" : "💭" }}</span>
          {{ showMessageBox ? "隱藏輸入框" : "顯示輸入框" }}
        </div>
        <div class="menu-divider"></div>
        <div class="menu-item" @click="openAppearancePanelAt(contextMenu.x, contextMenu.y)">
          <span class="menu-icon">✨</span>外貌設置
        </div>
        <div class="menu-item" @click="resetModelState">
          <span class="menu-icon">🔄</span>重置表情
        </div>
        <div class="menu-item" @click="onToggleSystemAudio">
          <span class="menu-icon">{{ rhythmShowListening ? "🔇" : "🎵" }}</span>
          {{ rhythmShowListening ? "關閉音頻律動" : "開啟音頻律動" }}
        </div>
        <div class="menu-divider"></div>
        <div class="menu-item" @click="openHistoryPanelAt(contextMenu.x, contextMenu.y)">
          <span class="menu-icon">📖</span>歷史記錄
        </div>
        <div class="menu-item danger" @click="closeLuna">
          <span class="menu-icon">🌙</span>關閉 Luna
        </div>
      </div>
    </Transition>

    <!-- ===== 外貌設置面板 ===== -->
    <Transition name="panel">
      <div
        v-if="appearancePanel.visible"
        class="appearance-panel glass-panel"
        :style="{ left: appearancePanel.x + 'px', top: appearancePanel.y + 'px' }"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      >
        <div class="panel-header drag-handle" @pointerdown="onAppearanceDragStart">
          <span class="panel-title">✨ 外貌設置</span>
          <button class="icon-btn close-btn" @click="closeAppearancePanel">×</button>
        </div>
        <div class="panel-body">
          <div class="appearance-controls">
            <label
              v-for="file in APPEARANCE_FILES"
              :key="file"
              class="appearance-item"
              :class="{ enabled: appearanceEnabled[file] }"
            >
              <input
                type="checkbox"
                v-model="appearanceEnabled[file]"
                @change="onAppearanceToggleUI(file)"
              />
              <span class="item-dot"></span>
              <span class="file-name">{{ displayAppearanceName(file) }}</span>
            </label>
          </div>
        </div>
        <div class="panel-footer">
          <button class="cute-btn small" @click="applyAllEnabledUI">應用</button>
          <button class="cute-btn small ghost" @click="disableAllUI">全部禁用</button>
        </div>
        <Transition name="hint">
          <div class="appearance-hint" v-if="appearanceHint">{{ appearanceHint }}</div>
        </Transition>
      </div>
    </Transition>

    <!-- ===== 歷史記錄面板 ===== -->
    <Transition name="panel">
      <div
        v-if="historyPanel.visible"
        class="history-panel glass-panel"
        :style="{ left: historyPanel.x + 'px', top: historyPanel.y + 'px' }"
        ref="historyPanelRef"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
        @click.stop
        @pointerdown.stop
      >
        <div class="panel-header drag-handle" @pointerdown="onHistoryDragStart">
          <div class="history-nav">
            <button class="icon-btn nav-btn" @click="changeMonth(-1)" title="上個月">‹</button>
            <select class="cute-select" v-model="historyPanel.selectedYear" @change="onYearChange">
              <option v-for="y in historyPanel.years" :key="y" :value="y">{{ y }}</option>
            </select>
            <select class="cute-select" v-model="historyPanel.selectedMonth" @change="onMonthChange">
              <option v-for="m in historyPanel.months" :key="m" :value="m">{{ m }} 月</option>
            </select>
            <button class="icon-btn nav-btn" @click="changeMonth(1)" title="下個月">›</button>
          </div>
          <button class="icon-btn close-btn" @click="closeHistoryPanel">×</button>
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
    </Transition>

    <!-- ===== 聊天記錄詳情面板 ===== -->
    <Transition name="panel">
      <div
        v-if="detailVisible"
        class="chat-detail-panel glass-panel"
        :style="{ left: detailPos.x + 'px', top: detailPos.y + 'px' }"
        @mousedown.stop
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      >
        <div class="panel-header drag-handle1" @mousedown="startDrag">
          <span class="panel-title">📖 {{ selectedHistoryDate }}</span>
          <button class="icon-btn close-btn" @click.stop="detailVisible = false">×</button>
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
    </Transition>

    <!-- ===== PIXI Canvas ===== -->
    <!-- 默認 pointer-events: none，只有命中模型時才攔截 -->
    <div ref="wrapperRef" class="interactive-wrapper">
      <canvas
        ref="canvasRef"
        class="live2d-canvas"
        @contextmenu.prevent="onCanvasRightClick"
      ></canvas>
    </div>

    <!-- ===== 音頻律動指示器 ===== -->
    <Transition name="fade">
      <div v-if="rhythmShowListening" class="rhythm-indicator" @mouseenter="uiEnter" @mouseleave="uiLeave">
        <div class="rhythm-bars">
          <span v-for="i in 5" :key="i" class="rhythm-bar" :style="{ animationDelay: `${i * 0.1}s` }"></span>
        </div>
        <span class="rhythm-label">音頻律動中</span>
      </div>
    </Transition>

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
const inputFocused = ref(false);

// 當前情緒狀態（影響輸入框顏色主題）
const currentEmotion = ref("default");

// Live2D 核心對象（非響應式）
let app = null;
let container = null;
let model = null;

// 表情緩存
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

/* ================= UI 橋接方法 ================= */
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

// 加載中省略號動畫計時器
let dotsTimer = null;

watch(isLoading, (loading) => {
  clearInterval(dotsTimer);
  dotsTimer = null;
  if (!loading) return;
  // loading 狀態由模板中跳動點動畫接管，不再需要 placeholder 動畫
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

  // 更新當前情緒，驅動輸入框主題色
  if (em) {
    currentEmotion.value = em.toLowerCase();
    try { await applyEmotionExpressions(em); } catch {}
    // 3秒後恢復默認主題色
    setTimeout(() => { currentEmotion.value = "default"; }, 3000);
  }

  await sendReplyAsBubbles(replyText, { interval: 1000, duration: 5000 }, em);
  lastReply.value.text = replyText;
}

/** 網絡錯誤輕處理 */
function handleNetworkError() {
  lastReply.value.loading = false;
  showAppearanceHint("網絡請求失敗");
}

/* ================= 發送消息 ================= */
async function onSend() {
  if (sending.value || isLoading.value) return;
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
  selectedDay: null,
  availableDates: [],
  availableDatesSet: new Set(),
  loading: false,
});

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
  contextMenu.value.visible = false;
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
const detailPos = ref({ x: window.innerWidth / 2 - 180, y: 100 });

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
    chatRecords.value = (Array.isArray(rawList) ? rawList : [])
      .filter((item) => typeof item === "string")
      .map((item) => {
        const parts = item.split(":");
        const time = parts.slice(-3).join(":");
        const role = parts[0];
        const content = parts.slice(1, -3).join(":");
        return { role, content, time };
      });
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

/* ================= 模型命中測試 ================= */
/**
 * 判斷全局坐標是否在模型範圍內
 * 用於決定是否攔截鼠標事件，實現精準穿透
 */
function isPointInsideModel(clientX, clientY) {
  if (!model || !canvasRef.value) return false;
  const rect = canvasRef.value.getBoundingClientRect();
  const gx = clientX - rect.left;
  const gy = clientY - rect.top;
  return model.getBounds().contains(gx, gy);
}

/* ================= 拖拽模型 ================= */
let dragging = false;
let lastPos = { x: 0, y: 0 };

// 全局 pointermove / pointerup 處理（掛在 document 而非 canvas）
function onDocPointerMove(e) {
  // 視線追蹤
  if (trackingEnabled.value && lookOriginLocal && model) {
    const rect = canvasRef.value.getBoundingClientRect();
    繼續從斷點處輸出：

