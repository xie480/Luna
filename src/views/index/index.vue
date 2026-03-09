<template>
  <div class="app-root">

    <!-- ===== 背景装饰粒子 ===== -->
    <div class="bg-particles">
      <span v-for="i in 18" :key="i" class="particle" :style="particleStyle(i)"></span>
    </div>

    <!-- ===== 调试 UI ===== -->
    <div
      v-if="showDebugUI"
      ref="uiRef"
      class="debug-ui"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <button class="cute-btn" @click="toggleTracking">
        <span class="btn-dot" :class="trackingEnabled ? 'green' : 'red'"></span>
        {{ trackingEnabled ? "视线追踪 ON" : "视线追踪 OFF" }}
      </button>
      <button class="cute-btn" @click="startSetOrigin">
        {{ isSettingOrigin ? "点击画布设置..." : "设置原点" }}
      </button>
      <button class="cute-btn" @click="clearOrigin">清除原点</button>
    </div>

    <!-- ===== 输入框 ===== -->
    <transition name="msgbox-fade">
      <div
        v-if="showMessageBox"
        class="messageBox"
        :class="[`emotion-${currentEmotion}`]"
        ref="messageBoxRef"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
        @contextmenu.stop
        @click.stop
      >
        <!-- 情绪指示灯 -->
        <div class="emotion-indicator" :class="`ei-${currentEmotion}`">
          <span class="ei-dot"></span>
          <span class="ei-label">{{ emotionLabel }}</span>
        </div>

        <div class="fileUploadWrapper">
          <label for="file">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 337 337">
              <circle stroke-width="20" stroke="#aaa" fill="none" r="158.5" cy="168.5" cx="168.5"></circle>
              <path stroke-linecap="round" stroke-width="25" stroke="#aaa" d="M167.759 79V259"></path>
              <path stroke-linecap="round" stroke-width="25" stroke="#aaa" d="M79 167.138H259"></path>
            </svg>
            <span class="tooltip">上传文件</span>
          </label>
          <input type="file" id="file" name="file" />
        </div>

        <input
          required
          :disabled="isLoading"
          :placeholder="isLoading ? '' : idlePlaceholder"
          type="text"
          id="messageInput"
          v-model="input"
          @keydown.enter.prevent="onSend"
        />

        <!-- 可爱加载动画 -->
        <div v-if="isLoading" class="loading-dots">
          <span></span><span></span><span></span>
        </div>

        <button id="sendButton" @click="onSend" :disabled="sending" class="send-btn-cute">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 664 663">
            <path fill="none" d="M646.293 331.888L17.7538 17.6187L155.245 331.888M646.293 331.888L17.753 646.157L155.245 331.888M646.293 331.888L318.735 330.228L155.245 331.888"></path>
            <path stroke-linejoin="round" stroke-linecap="round" stroke-width="33.67" stroke="#fff" d="M646.293 331.888L17.7538 17.6187L155.245 331.888M646.293 331.888L17.753 646.157L155.245 331.888M646.293 331.888L318.735 330.228L155.245 331.888"></path>
          </svg>
        </button>
      </div>
    </transition>

    <!-- ===== 聊天气泡容器 ===== -->
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
        <span class="bubble-avatar">🌙</span>
        {{ bubble.text }}
      </div>
    </div>

    <!-- ===== 右键菜单 ===== -->
    <transition name="menu-pop">
      <div
        v-if="contextMenu.visible"
        ref="contextMenuRef"
        class="context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
        @click.stop
      >
        <div class="menu-header">
          <span class="menu-logo">🌙 Luna</span>
        </div>
        <div class="menu-item" @click="toggleDebugUI">
          <span class="mi-icon">{{ showDebugUI ? '🔴' : '🟢' }}</span>
          {{ showDebugUI ? "隐藏调试UI" : "显示调试UI" }}
        </div>
        <div class="menu-item" @click="toggleMessageBox">
          <span class="mi-icon">💬</span>
          {{ showMessageBox ? "隐藏输入框" : "显示输入框" }}
        </div>
        <div class="menu-item" @click="openAppearancePanelAt(contextMenu.x, contextMenu.y)">
          <span class="mi-icon">✨</span>外貌设置
        </div>
        <div class="menu-item" @click="resetModelState">
          <span class="mi-icon">🔄</span>重置模型表情
        </div>
        <div class="menu-item" @click="onToggleSystemAudio">
          <span class="mi-icon">{{ rhythmShowListening ? '🔇' : '🎵' }}</span>
          {{ rhythmShowListening ? "关闭音频律动" : "开启音频律动" }}
        </div>
        <div class="menu-item" @click="openHistoryPanelAt(contextMenu.x, contextMenu.y)">
          <span class="mi-icon">📖</span>历史记录
        </div>
        <div class="menu-divider"></div>
        <div class="menu-item danger" @click="closeLuna">
          <span class="mi-icon">💔</span>关闭 Luna
        </div>
      </div>
    </transition>

    <!-- ===== 外貌设置面板 ===== -->
    <div
      v-if="appearancePanel.visible"
      class="appearance-panel cute-panel"
      :style="{ left: appearancePanel.x + 'px', top: appearancePanel.y + 'px' }"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <div class="panel-header drag-handle" @pointerdown="onAppearanceDragStart">
        <span>✨ 外貌设置</span>
        <button class="close-x" @click="closeAppearancePanel">×</button>
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
        <button class="cute-btn small" @click="applyAllEnabledUI">应用已启用</button>
        <button class="cute-btn small danger" @click="disableAllUI">全部禁用</button>
      </div>
      <div class="appearance-hint" v-if="appearanceHint">{{ appearanceHint }}</div>
    </div>

    <!-- ===== 历史记录面板 ===== -->
    <div
      v-if="historyPanel.visible"
      class="history-panel cute-panel"
      :style="{ left: historyPanel.x + 'px', top: historyPanel.y + 'px' }"
      ref="historyPanelRef"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
      @click.stop
      @pointerdown.stop
    >
      <div class="panel-header drag-handle" @pointerdown="onHistoryDragStart">
        <div class="header-left">
          <button class="nav-btn" @click="changeMonth(-1)">‹</button>
          <button class="nav-btn" @click="changeMonth(1)">›</button>
          <select v-model="historyPanel.selectedYear" @change="onYearChange">
            <option v-for="y in historyPanel.years" :key="y" :value="y">{{ y }}</option>
          </select>
          <select v-model="historyPanel.selectedMonth" @change="onMonthChange">
            <option v-for="m in historyPanel.months" :key="m" :value="m">{{ m }} 月</option>
          </select>
        </div>
        <button class="close-x" @click="closeHistoryPanel">×</button>
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
        >{{ d }}</div>
      </div>
    </div>

    <!-- ===== 聊天记录详情面板 ===== -->
    <div
      v-if="detailVisible"
      class="chat-detail-panel cute-panel"
      :style="{ left: detailPos.x + 'px', top: detailPos.y + 'px' }"
      @mousedown.stop
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <div class="panel-header drag-handle1" @mousedown="startDrag">
        <span class="h2">📖 {{ selectedHistoryDate }}</span>
        <button class="close-x" @click.stop="detailVisible = false">×</button>
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
    <div ref="wrapperRef" class="interactive-wrapper">
      <canvas ref="canvasRef" @contextmenu.prevent="onCanvasRightClick"></canvas>
    </div>

    <!-- ===== Luna 入场遮罩 ===== -->
    <transition name="luna-intro">
      <div v-if="lunaIntroVisible" class="luna-intro-mask">
        <div class="luna-intro-text">
          <span class="luna-dot" v-for="i in 3" :key="i"></span>
        </div>
      </div>
    </transition>

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

/* ================= 基础状态 ================= */
const showDebugUI = ref(false);
const showMessageBox = ref(false);
const trackingEnabled = ref(true);
const isSettingOrigin = ref(false);
const lunaIntroVisible = ref(true);

// emotion 状态
const currentEmotion = ref("neutral");
const EMOTION_LABEL_MAP = {
  neutral:   "平静",
  happy:     "开心",
  sad:       "难过",
  angry:     "生气",
  surprised: "惊讶",
  shy:       "害羞",
  Solemn:    "庄重",
};
const emotionLabel = computed(() => EMOTION_LABEL_MAP[currentEmotion.value] || currentEmotion.value);

// 粒子样式（纯装饰，pointer-events: none）
function particleStyle(i) {
  const size  = 4 + (i % 5) * 3;
  const left  = (i * 37 + 11) % 100;
  const top   = (i * 53 + 7)  % 100;
  const delay = (i * 0.4) % 3;
  const dur   = 3 + (i % 4);
  return {
    width:             `${size}px`,
    height:            `${size}px`,
    left:              `${left}%`,
    top:               `${top}%`,
    animationDelay:    `${delay}s`,
    animationDuration: `${dur}s`,
  };
}

// Live2D 核心对象（非响应式）
let app       = null;
let container = null;
let model     = null;

// 表情缓存
const expressionCache = new Map();

/* ================= 气泡（composable） ================= */
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

/* ================= 音频律动（composable） ================= */
const {
  showSystemAudioListening: rhythmShowListening,
  toggleSystemAudio,
  dispose: disposeRhythm,
} = useRhythm();

function getCoreModel() {
  return model?.internalModel?.coreModel ?? null;
}

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

/* ================= 聊天输入 ================= */
const input = ref("");
const sending = ref(false);
const lastReply = ref({ loading: false, text: "" });

const isLoading = computed(() => sending.value || lastReply.value.loading);
const idlePlaceholder = "和 Luna 说点什么吧…";
const loadingPlaceholder = ref("…");

let dotsTimer = null;
watch(isLoading, (loading) => {
  clearInterval(dotsTimer);
  dotsTimer = null;
  if (!loading) loadingPlaceholder.value = idlePlaceholder;
});

/* ================= 响应处理 ================= */
function normalizeResponse(res) {
  const data = res?.data ?? res;
  if (typeof data === "string") {
    try { return JSON.parse(data); } catch { return { reply: data }; }
  }
  return data;
}

async function handleModelReply(res) {
  console.log("[Luna] 模型已返回内容", res);
  lastReply.value.loading = false;
  if (!res) return;

  const em = res.emotion || "";
  const replyText = res.reply || res.text || "";
  if (!replyText) return;

  if (em) {
    currentEmotion.value = em;
    try { await applyEmotionExpressions(em); } catch {}
  }

  await sendReplyAsBubbles(replyText, { interval: 1000, duration: 5000 });
  lastReply.value.text = replyText;
}

function handleNetworkError() {
  lastReply.value.loading = false;
  showAppearanceHint("网络请求失败");
}

/* ================= 发送消息 ================= */
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
    console.error("[Luna] 发送失败", e);
    handleNetworkError();
  } finally {
    sending.value = false;
    await nextTick();
  }
}

/* ================= 启动 / 关闭 ================= */
async function callStartup() {
  lastReply.value.loading = true;
  try {
    const res = await startupApi();
    await handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 启动失败", e);
    handleNetworkError();
  }
}

async function callShutdown() {
  lastReply.value.loading = true;
  try {
    const res = await shutdownApi();
    await handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 关闭失败", e);
  } finally {
    lastReply.value.loading = false;
  }
}

/* ================= 历史记录面板 ================= */
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
    console.error("[History] fetchHistoryForMonth 失败", e);
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

function onYearChange()  { fetchHistoryForMonth(historyPanel.value.selectedYear, historyPanel.value.selectedMonth); }
function onMonthChange() { fetchHistoryForMonth(historyPanel.value.selectedYear, historyPanel.value.selectedMonth); }

function openHistoryPanelAt(x, y) {
  historyPanel.value.visible = true;
  historyPanel.value.x = x;
  historyPanel.value.y = y;
  const now = new Date();
  historyPanel.value.selectedYear  = historyPanel.value.selectedYear  || now.getFullYear();
  historyPanel.value.selectedMonth = historyPanel.value.selectedMonth || now.getMonth() + 1;
  fetchHistoryForMonth(historyPanel.value.selectedYear, historyPanel.value.selectedMonth);
}
function closeHistoryPanel() { historyPanel.value.visible = false; }

let draggingHistoryPanel = false;
let historyDragStart   = { x: 0, y: 0 };
let historyPanelStart  = { x: 0, y: 0 };

function onHistoryDragStart(e) {
  if (e.button !== 0) return;
  draggingHistoryPanel = true;
  historyDragStart  = { x: e.clientX, y: e.clientY };
  historyPanelStart = { x: historyPanel.value.x, y: historyPanel.value.y };
  document.addEventListener("pointermove", onHistoryDragMove);
  document.addEventListener("pointerup",   onHistoryDragEnd);
}
function onHistoryDragMove(e) {
  if (!draggingHistoryPanel) return;
  const dx = e.clientX - historyDragStart.x;
  const dy = e.clientY - historyDragStart.y;
  historyPanel.value.x = Math.min(window.innerWidth  - 320, Math.max(0, historyPanelStart.x + dx));
  historyPanel.value.y = Math.min(window.innerHeight - 300, Math.max(0, historyPanelStart.y + dy));
}
function onHistoryDragEnd() {
  draggingHistoryPanel = false;
  document.removeEventListener("pointermove", onHistoryDragMove);
  document.removeEventListener("pointerup",   onHistoryDragEnd);
}

/* ================= 聊天记录详情 ================= */
const detailVisible       = ref(false);
const chatRecords         = ref([]);
const selectedHistoryDate = ref("");
const detailPos           = ref({ x: window.innerWidth / 2 - 175, y: 100 });

let isDragging = false;
let startX = 0;
let startY = 0;

function startDrag(e) {
  isDragging = true;
  startX = e.clientX - detailPos.value.x;
  startY = e.clientY - detailPos.value.y;
  window.addEventListener("mousemove", onDragging);
  window.addEventListener("mouseup",   stopDrag);
}
function onDragging(e) {
  if (!isDragging) return;
  detailPos.value.x = e.clientX - startX;
  detailPos.value.y = e.clientY - startY;
}
function stopDrag() {
  isDragging = false;
  window.removeEventListener("mousemove", onDragging);
  window.removeEventListener("mouseup",   stopDrag);
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
        const parts   = item.split(":");
        const time    = parts.slice(-3).join(":");
        const role    = parts[0];
        const content = parts.slice(1, -3).join(":");
        return { role, content, time };
      });
    detailVisible.value = true;
  } catch (e) {
    console.error("[History] 获取聊天详情失败:", e);
  }
}

/* ================= 右键菜单 ================= */
function onRightClick(e) {
  if (uiRef.value?.contains(e.target))         return;
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
    if (x + width  > window.innerWidth)  contextMenu.value.x = window.innerWidth  - width  - 10;
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
let dragStart  = { x: 0, y: 0 };
let panelStart = { x: 0, y: 0 };

function onAppearanceDragStart(e) {
  if (e.button !== 0) return;
  draggingAppearance = true;
  dragStart  = { x: e.clientX, y: e.clientY };
  panelStart = { x: appearancePanel.value.x, y: appearancePanel.value.y };
  document.addEventListener("pointermove", onAppearanceDragMove);
  document.addEventListener("pointerup",   onAppearanceDragEnd);
}
function onAppearanceDragMove(e) {
  if (!draggingAppearance) return;
  const maxX = window.innerWidth  - 420;
  const maxY = window.innerHeight - 360;
  appearancePanel.value.x = Math.min(maxX, Math.max(0, panelStart.x + e.clientX - dragStart.x));
  appearancePanel.value.y = Math.min(maxY, Math.max(0, panelStart.y + e.clientY - dragStart.y));
}
function onAppearanceDragEnd() {
  draggingAppearance = false;
  document.removeEventListener("pointermove", onAppearanceDragMove);
  document.removeEventListener("pointerup",   onAppearanceDragEnd);
  updatePetState();
}

function openAppearancePanelAt(x, y) {
  const panelWidth  = 420;
  const panelHeight = 360;
  appearancePanel.value.x = x + panelWidth  > window.innerWidth  ? window.innerWidth  - panelWidth  - 10 : x;
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
let overUI    = false;

function updatePetState() {
  if (overModel || overUI) window.pet?.enter();
  else window.pet?.leave();
}
function uiEnter() { overUI = true;  updatePetState(); }
function uiLeave() { overUI = false; updatePetState(); }

watch(showMessageBox, (v) => { if (v) window.pet?.enter(); else updatePetState(); });
watch(showDebugUI,   (v) => { if (v) window.pet?.enter(); else updatePetState(); });

/* ================= 拖拽模型（仅模型本身） ================= */
let dragging = false;
let lastPos  = { x: 0, y: 0 };

function isPointInsideModel(globalPoint) {
  if (!model) return false;
  return model.getBounds().contains(globalPoint.x, globalPoint.y);
}

function onPointerDown(e) {
  const oe = e.data?.originalEvent;
  if (!oe || oe.button !== 0) return;
  const gp = e.data.global;
  if (!isPointInsideModel(gp)) return;
  dragging = true;
  lastPos  = { x: gp.x, y: gp.y };
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

/* ================= 滚轮缩放（仅命中模型时） ================= */
function onWheel(ev) {
  const rect        = canvasRef.value.getBoundingClientRect();
  const globalPoint = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  if (!isPointInsideModel(globalPoint)) return;
  ev.preventDefault();
  const factor   = ev.deltaY > 0 ? 0.95 : 1.05;
  const newScale = Math.min(10, Math.max(0.05, (container.scale.x || 1) * factor));
  const localPoint = container.toLocal(globalPoint, app.stage);
  container.scale.set(newScale);
  const newGlobal = container.toGlobal(localPoint);
  container.position.x += globalPoint.x - newGlobal.x;
  container.position.y += globalPoint.y - newGlobal.y;
}

/* ================= 视线追踪 ================= */
const PARAM_CONFIG = {
  HEAD_X: { param: "ParamAngleX",   range: [-30, 30] },
  HEAD_Y: { param: "ParamAngleY",   range: [-30, 30] },
  EYE_X:  { param: "ParamEyeBallX", range: [-1, 1]   },
  EYE_Y:  { param: "ParamEyeBallY", range: [-1, 1]   },
  BREATH: { param: "ParamBreath",   range: [0, 1]    },
};

const LOOK_ORIGIN_KEY = "live2d:look-origin";
let lookOriginLocal = null;

function applyLookAt(dx, dy) {
  const core = getCoreModel();
  if (!core) return;
  const nx = Math.max(-1, Math.min(1, dx / (app.renderer.width  / 2)));
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
  const rect   = canvasRef.value.getBoundingClientRect();
  const world  = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  const local  = container.toLocal(world, app.stage);
  applyLookAt(local.x - lookOriginLocal.x, local.y - lookOriginLocal.y);
}

function onCanvasClick(ev) {
  if (!isSettingOrigin.value) return;
  const rect   = canvasRef.value.getBoundingClientRect();
  const world  = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
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

/* ================= 调试 UI ================= */
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

/* ================= 呼吸动画 ================= */
let breathTickerFn = null;
function startBreath() {
  const breathStart = performance.now() / 1000;
  breathTickerFn = () => {
    const core = getCoreModel();
    if (!core) return;
    const t   = performance.now() / 1000 - breathStart;
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
const INITIAL_EMOTION = "Solemn";
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
      console.warn("[Luna] resetToSolemn 恢复失败:", id, e);
    }
  }
  currentEmotionMeta = {};
  await new Promise((r) => requestAnimationFrame(r));
}

function tweenParameters(core, targetValues, duration = 200) {
  return new Promise((resolve) => {
    const startTime  = performance.now();
    const fromValues = {};
    for (const id in targetValues) {
      fromValues[id] = core.getParameterValueById(id) ?? 0;
    }
    function step(now) {
      const t = Math.min((now - startTime) / duration, 1);
      const k = t * t * (3 - 2 * t);
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
  const targetValues  = {};
  const thisApplyPrev = {};
  for (const cnName of names) {
    const expJson = expressionCache.get(cnName);
    if (!expJson) continue;
    (expJson.Parameters || []).forEach(({ Id, Value, Blend }) => {
      const base = targetValues[Id] ?? core.getParameterValueById(Id) ?? 0;
      if (!(Id in thisApplyPrev)) thisApplyPrev[Id] = base;
      if (Blend === "Add")            targetValues[Id] = base + Value;
      else if (Blend === "Multiply")  targetValues[Id] = base * Value;
      else                            targetValues[Id] = Value;
    });
  }
  await tweenParameters(core, targetValues, 180);
  currentEmotionMeta = thisApplyPrev;
  await applyAllEnabled(getCoreModel());
}

/* ================= 预加载表情文件 ================= */
async function preloadExpressions() {
  const allFiles = [
    "眼-生气", "脸红2隐藏", "脸黑", "眼-哭哭", "眼-泪眼汪汪",
    "眼-眩晕流汗", "脸红", "眼-平静死鱼眼", "嘴-平静v形（不可张开",
    "眼-星星眼", "脸红-痴汉嘴（兼容吐舌", "眼-爱心眼",
  ];
  await Promise.all(
    allFiles.map(async (name) => {
      try {
        const res = await fetch(`/models/luna/${encodeURIComponent(name)}.exp3.json`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        expressionCache.set(name, await res.json());
      } catch (e) {
        console.error(`[Live2D] 加载失败: ${name}`, e);
      }
    })
  );
}

/* ================= 重置模型状态 ================= */
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

/* ================= 关闭 Luna ================= */
function closeLuna() {
  try {
    stopBreath();
    app?.destroy(true);
    window.pet?.leave?.();
  } catch (e) {
    console.warn("[Luna] 关闭出错", e);
  }
  if (wrapperRef.value) wrapperRef.value.innerHTML = "";
  showMessageBox.value      = false;
  contextMenu.value.visible = false;
}

/* ================= 等待模型就绪 ================= */
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
    view:            canvasRef.value,
    backgroundAlpha: 0,
    resizeTo:        wrapperRef.value,
  });

  container = new PIXI.Container();
  app.stage.addChild(container);

  model = await Live2DModel.from("/models/luna/jk盐.model3.json", {
    autoInteract: false,
    ticker:       PIXI.Ticker.shared,
  });

  model.scale.set(0.1);
  model.anchor.set(0.5, 1);
  model.x           = app.renderer.width / 2;
  model.y           = app.renderer.height;
  model.interactive = true;
  model.cursor      = "pointer";

  // 入场动画：先设为透明+偏下，再 gsap 淡入
  model.alpha = 0;
  model.y     = app.renderer.height + 60;

  model
    .on("pointerdown",     onPointerDown)
    .on("pointermove",     onPointerMove)
    .on("pointerup",       onPointerUp)
    .on("pointerupoutside", onPointerUp);

  model.on("pointerover", () => { overModel = true;  updatePetState(); });
  model.on("pointerout",  () => { overModel = false; updatePetState(); });
  model.on("rightclick",  (e) => {
    const rect = canvasRef.value.getBoundingClientRect();
    showContextMenu(rect.left + e.data.global.x, rect.top + e.data.global.y);
  });

  container.addChild(model);
  loadOrigin();

  wrapperRef.value.addEventListener("pointermove", onGlobalPointerMove);
  wrapperRef.value.addEventListener("pointerdown", onCanvasClick);
  wrapperRef.value.addEventListener("wheel",       onWheel, { passive: false });
  document.addEventListener("click",       handleClickOutside);
  document.addEventListener("contextmenu", onRightClick);

  await preloadExpressions();
  startBreath();

  loadAppearanceState();
  await waitForModelReady(5000);
  await nextTick();
  await applyAllEnabled(getCoreModel());

  // Luna 入场动画
  gsap.to(model, {
    alpha:    1,
    y:        app.renderer.height,
    duration: 1.4,
    ease:     "power3.out",
    onComplete: () => {
      lunaIntroVisible.value = false;
    },
  });

  applyEmotionExpressions(INITIAL_EMOTION);
  callStartup();
});

onBeforeUnmount(() => {
  clearInterval(dotsTimer);
  dotsTimer = null;
  stopBreath();
  disposeRhythm(getCoreModel(), trackingEnabled);
  app?.destroy(true);
  callShutdown();
  document.removeEventListener("click",       handleClickOutside);
  document.removeEventListener("contextmenu", onRightClick);
});
</script>

<style scoped>
/* ================= 全局根 ================= */
.app-root {
  width: 100vw;
  height: 100vh;
  position: relative;
  overflow: hidden;
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
  background: transparent;
}

/* ================= 背景粒子 ================= */
.bg-particles {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}
.particle {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255,255,255,0.18) 0%, rgba(200,200,200,0.06) 100%);
  animation: particleFloat linear infinite;
  pointer-events: none;
}
@keyframes particleFloat {
  0%   { transform: translateY(0px)   scale(1);   opacity: 0.5; }
  50%  { transform: translateY(-18px) scale(1.1); opacity: 0.9; }
  100% { transform: translateY(0px)   scale(1);   opacity: 0.5; }
}

/* ================= Canvas 容器 ================= */
.interactive-wrapper {
  width: 100%;
  height: 100%;
  position: relative;
  z-index: 1;
  pointer-events: none;
}
.interactive-wrapper canvas {
  pointer-events: auto;
}

/* ================= 气泡 ================= */
.bubble-stack {
  position: fixed;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  transform: translate(-50%, -100%);
  pointer-events: none;
  z-index: 1002;
}
.css-chat-bubble {
  max-width: 280px;
  padding: 10px 16px;
  width: fit-content;
  background: linear-gradient(135deg, rgba(255,255,255,0.96), rgba(240,240,240,0.92));
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 20px 20px 20px 4px;
  color: #222;
  font-size: 13.5px;
  line-height: 1.6;
  display: flex;
  align-items: center;
  gap: 8px;
  word-break: break-word;
  box-shadow: 0 8px 24px rgba(0,0,0,0.13), 0 1.5px 4px rgba(0,0,0,0.07);
  animation: bubbleIn 0.28s cubic-bezier(0.34,1.56,0.64,1) both;
}
.bubble-avatar {
  font-size: 16px;
  flex-shrink: 0;
  animation: bubbleAvatarBounce 1.8s ease-in-out infinite;
}
@keyframes bubbleAvatarBounce {
  0%, 100% { transform: translateY(0);   }
  50%       { transform: translateY(-3px); }
}
@keyframes bubbleIn {
  from { opacity: 0; transform: scale(0.8) translateY(10px); }
  to   { opacity: 1; transform: scale(1)   translateY(0);    }
}
@keyframes bubbleOut {
  from { opacity: 1; transform: scale(1);    }
  to   { opacity: 0; transform: scale(0.88) translateY(6px); }
}
.css-chat-bubble.leaving {
  animation: bubbleOut 0.2s ease-in forwards;
}

/* ================= 输入框 ================= */
.messageBox {
  position: absolute;
  top: 800px;
  left: 490px;
  width: 700px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a1a1a 0%, #242424 100%);
  padding: 0 14px;
  border-radius: 28px;
  border: 1.5px solid rgba(255,255,255,0.10);
  z-index: 1001;
  pointer-events: auto;
  box-shadow: 0 8px 32px rgba(0,0,0,0.35), 0 1.5px 4px rgba(0,0,0,0.18);
  transition: border-color 0.4s ease, box-shadow 0.4s ease;
  gap: 8px;
}

.messageBox.emotion-happy     { border-color: rgba(255,220,80,0.45);  box-shadow: 0 8px 32px rgba(255,220,80,0.12),  0 1.5px 4px rgba(0,0,0,0.18); }
.messageBox.emotion-sad       { border-color: rgba(100,160,255,0.45); box-shadow: 0 8px 32px rgba(100,160,255,0.12), 0 1.5px 4px rgba(0,0,0,0.18); }
.messageBox.emotion-angry     { border-color: rgba(255,90,90,0.45);   box-shadow: 0 8px 32px rgba(255,90,90,0.12),   0 1.5px 4px rgba(0,0,0,0.18); }
.messageBox.emotion-shy       { border-color: rgba(255,150,200,0.45); box-shadow: 0 8px 32px rgba(255,150,200,0.12), 0 1.5px 4px rgba(0,0,0,0.18); }
.messageBox.emotion-surprised { border-color: rgba(180,120,255,0.45); box-shadow: 0 8px 32px rgba(180,120,255,0.12), 0 1.5px 4px rgba(0,0,0,0.18); }
.messageBox.emotion-Solemn    { border-color: rgba(255,255,255,0.12); }
.messageBox.emotion-neutral   { border-color: rgba(255,255,255,0.10); }

.messageBox:focus-within {
  border-color: rgba(255,255,255,0.28);
  box-shadow: 0 8px 36px rgba(0,0,0,0.4), 0 0 0 3px rgba(255,255,255,0.04);
}

/* 情绪指示灯 */
.emotion-indicator {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  padding: 3px 8px;
  border-radius: 12px;
  background: rgba(255,255,255,0.05);
  transition: background 0.3s ease;
}
.ei-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #888;
  animation: eiPulse 2s ease-in-out infinite;
  transition: background 0.4s ease;
}
.ei-label {
  font-size: 10px;
  color: rgba(255,255,255,0.45);
  white-space: nowrap;
  letter-spacing: 0.03em;
}
.ei-happy     .ei-dot { background: #ffd84f; }
.ei-sad       .ei-dot { background: #64a0ff; }
.ei-angry     .ei-dot { background: #ff5a5a; }
.ei-shy       .ei-dot { background: #ff96c8; }
.ei-surprised .ei-dot { background: #b478ff; }
.ei-Solemn    .ei-dot { background: #aaa; }
.ei-neutral   .ei-dot { background: #888; }

@keyframes eiPulse {
  0%, 100% { opacity: 1;   transform: scale(1);    }
  50%       { opacity: 0.5; transform: scale(0.82); }
}

/* 文件上传 */
.fileUploadWrapper {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
#file { display: none; }
.fileUploadWrapper label {
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: 4px;
  border-radius: 8px;
  transition: background 0.2s;
}
.fileUploadWrapper label:hover { background: rgba(255,255,255,0.08); }
.fileUploadWrapper label svg   { height: 18px; }
.fileUploadWrapper label svg path,
.fileUploadWrapper label svg circle { transition: all 0.25s; }
.fileUploadWrapper label:hover svg path   { stroke: #fff; }
.fileUploadWrapper label:hover svg circle { stroke: #fff; }
.fileUploadWrapper label:hover .tooltip   { display: block; opacity: 1; }

.tooltip {
  position: absolute;
  top: -38px;
  left: 50%;
  transform: translateX(-50%);
  display: none;
  opacity: 0;
  color: #fff;
  font-size: 10px;
  white-space: nowrap;
  background: rgba(0,0,0,0.85);
  padding: 5px 10px;
  border-radius: 8px;
  border: 1px solid rgba(255,255,255,0.08);
  box-shadow: 0 4px 12px rgba(0,0,0,0.4);
  pointer-events: none;
}

/* 输入框主体 */
#messageInput {
  flex: 1;
  height: 100%;
  background: transparent;
  outline: none;
  border: none;
  color: #f0f0f0;
  font-size: 14px;
  letter-spacing: 0.01em;
  caret-color: #fff;
}
#messageInput::placeholder { color: rgba(255,255,255,0.28); }

/* 可爱加载动画 */
.loading-dots {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0 8px;
  flex-shrink: 0;
}
.loading-dots span {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(255,255,255,0.7);
  animation: dotJump 1.1s ease-in-out infinite;
}
.loading-dots span:nth-child(1) { animation-delay: 0s;    }
.loading-dots span:nth-child(2) { animation-delay: 0.18s; }
.loading-dots span:nth-child(3) { animation-delay: 0.36s; }

@keyframes dotJump {
  0%, 80%, 100% { transform: translateY(0)    scale(1);   opacity: 0.6; }
  40%            { transform: translateY(-8px) scale(1.2); opacity: 1;   }
}

/* 发送按钮 */
.send-btn-cute {
  width: 36px;
  height: 36px;
  background: rgba(255,255,255,0.10);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.15s ease, box-shadow 0.2s ease;
  flex-shrink: 0;
}
.send-btn-cute:hover:not(:disabled) {
  background: rgba(255,255,255,0.20);
  transform: scale(1.12);
  box-shadow: 0 4px 14px rgba(255,255,255,0.10);
}
.send-btn-cute:active:not(:disabled) { transform: scale(0.95); }
.send-btn-cute:disabled { opacity: 0.35; cursor: not-allowed; }
.send-btn-cute svg { height: 16px; }

/* msgbox 进场动画 */
.msgbox-fade-enter-active { transition: opacity 0.35s ease, transform 0.35s cubic-bezier(0.34,1.56,0.64,1); }
.msgbox-fade-leave-active { transition: opacity 0.2s ease,  transform 0.2s ease; }
.msgbox-fade-enter-from   { opacity: 0; transform: translateY(16px) scale(0.95); }
.msgbox-fade-leave-to     { opacity: 0; transform: translateY(8px)  scale(0.97); }

/* ================= 调试 UI ================= */
.debug-ui {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 1002;
  display: flex;
  gap: 8px;
  background: rgba(20,20,20,0.88);
  padding: 10px 12px;
  border-radius: 16px;
  box-shadow: 0 4px 18px rgba(0,0,0,0.3);
  border: 1px solid rgba(255,255,255,0.07);
  pointer-events: auto;
  backdrop-filter: blur(8px);
}
.cute-btn {
  background: rgba(255,255,255,0.07);
  color: #e8e8e8;
  border: 1px solid rgba(255,255,255,0.10);
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s, transform 0.15s, box-shadow 0.2s;
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.cute-btn:hover {
  background: rgba(255,255,255,0.14);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}
.cute-btn:active  { transform: scale(0.96); }
.cute-btn.small   { padding: 4px 10px; font-size: 12px; }
.cute-btn.danger  { border-color: rgba(255,80,80,0.3); color: #ff8888; }
.cute-btn.danger:hover { background: rgba(255,80,80,0.12); }

.btn-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.btn-dot.green { background: #6dff8a; box-shadow: 0 0 6px rgba(109,255,138,0.6); }
.btn-dot.red   { background: #ff6d6d; box-shadow: 0 0 6px rgba(255,109,109,0.6); }

/* ================= 右键菜单 ================= */
.context-menu {
  position: fixed;
  z-index: 1003;
  background: linear-gradient(160deg, #1c1c1c 0%, #212121 100%);
  border-radius: 16px;
  padding: 6px 0 8px;
  color: #e8e8e8;
  min-width: 172px;
  box-shadow: 0 12px 40px rgba(0,0,0,0.45), 0 2px 8px rgba(0,0,0,0.25);
  border: 1px solid rgba(255,255,255,0.07);
  font-size: 13.5px;
  pointer-events: auto;
  user-select: none;
  backdrop-filter: blur(12px);
}
.menu-header {
  padding: 8px 16px 6px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
  margin-bottom: 2px;
}
.menu-logo {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: rgba(255,255,255,0.55);
}
.menu-item {
  padding: 9px 16px;
  cursor: pointer;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.15s ease, padding-left 0.15s ease, color 0.15s ease;
}
.menu-item:hover {
  background: rgba(255,255,255,0.07);
  padding-left: 20px;
  color: #fff;
}
.menu-item.danger       { color: #ff8888; }
.menu-item.danger:hover { background: rgba(255,80,80,0.10); color: #ffaaaa; }
.mi-icon    { font-size: 14px; flex-shrink: 0; }
.menu-divider {
  height: 1px;
  background: rgba(255,255,255,0.06);
  margin: 4px 0;
}
.menu-pop-enter-active { transition: opacity 0.18s ease, transform 0.18s cubic-bezier(0.34,1.56,0.64,1); }
.menu-pop-leave-active { transition: opacity 0.12s ease, transform 0.12s ease; }
.menu-pop-enter-from   { opacity: 0; transform: scale(0.88) translateY(-6px); }
.menu-pop-leave-to     { opacity: 0; transform: scale(0.94) translateY(-3px); }

/* ================= 通用面板 ================= */
.cute-panel {
  background: linear-gradient(160deg, #1a1a1a 0%, #202020 100%);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 18px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.5), 0 2px 8px rgba(0,0,0,0.2);
  color: #e8e8e8;
  pointer-events: auto;
  backdrop-filter: blur(12px);
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px 10px;
  border-bottom: 1px solid rgba(255,255,255,0.05);
  font-weight: 600;
  font-size: 13px;
  cursor: move;
  user-select: none;
  letter-spacing: 0.04em;
  color: rgba(255,255,255,0.75);
  gap: 8px;
}
.close-x {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.08);
  color: #bbb;
  font-size: 16px;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, color 0.2s, transform 0.15s;
  flex-shrink: 0;
}
.close-x:hover {
  background: rgba(255,80,80,0.18);
  color: #ff8888;
  transform: rotate(90deg) scale(1.1);
}

/* ================= 外貌面板 ================= */
.appearance-panel {
  position: fixed;
  z-index: 1004;
  width: 420px;
  height: 360px;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.panel-body {
  padding: 10px 12px;
  overflow-y: auto;
  flex: 1;
}
.appearance-controls { display: flex; flex-direction: column; gap: 6px; }
.appearance-item      { display: flex; align-items: center; }
.checkbox-label {
  display: flex;
  gap: 8px;
  align-items: center;
  cursor: pointer;
  user-select: none;
}
.checkbox-label input[type="checkbox"] {
  width: 15px;
  height: 15px;
  accent-color: #fff;
}
.file-name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 340px;
  color: rgba(255,255,255,0.75);
}
.panel-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 8px 12px 12px;
  border-top: 1px solid rgba(255,255,255,0.04);
}
.appearance-hint {
  margin: 0 12px 10px;
  font-size: 11px;
  color: rgba(255,255,255,0.4);
  text-align: right;
  user-select: none;
  pointer-events: none;
  animation: appearanceHintFade 0.15s ease-out;
}
@keyframes appearanceHintFade {
  from { opacity: 0; transform: translateY(-2px); }
  to   { opacity: 1; transform: translateY(0);    }
}

/* ================= 历史面板 ================= */
.history-panel {
  position: absolute;
  width: 320px;
  z-index: 3000;
  padding: 0;
  overflow: hidden;
}
.header-left {
  display: flex;
  gap: 6px;
  align-items: center;
  flex: 1;
}
.nav-btn {
  background: rgba(255,255,255,0.07);
  border: 1px solid rgba(255,255,255,0.08);
  color: #fff;
  border-radius: 8px;
  width: 26px;
  height: 26px;
  cursor: pointer;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, transform 0.15s;
  flex-shrink: 0;
}
.nav-btn:hover { background: rgba(255,255,255,0.14); transform: scale(1.1); }
.history-panel select {
  background: rgba(255,255,255,0.06);
  color: #e8e8e8;
  border: 1px solid rgba(255,255,255,0.07);
  padding: 3px 7px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  outline: none;
}
.history-panel select option {
  background: #2a2a2a;
  color: #fff;
}
.dates-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 5px;
  padding: 10px;
}
.date-cell {
  width: 34px;
  height: 34px;
  line-height: 34px;
  text-align: center;
  border-radius: 10px;
  background: rgba(255,255,255,0.04);
  cursor: pointer;
  font-size: 12px;
  transition: background 0.18s, transform 0.15s, box-shadow 0.18s;
  user-select: none;
}
.date-cell.available {
  background: rgba(255,255,255,0.10);
  color: #fff;
  font-weight: 600;
}
.date-cell.available:hover {
  background: rgba(255,255,255,0.22);
  transform: scale(1.1);
}
.date-cell.selected {
  background: #fff;
  color: #111;
  font-weight: bold;
  box-shadow: 0 4px 14px rgba(255,255,255,0.18);
  transform: scale(1.08);
}
.date-cell.disabled {
  background: rgba(255,255,255,0.02);
  color: rgba(255,255,255,0.18);
  cursor: not-allowed;
}

/* ================= 聊天记录详情面板 ================= */
.chat-detail-panel {
  position: fixed;
  width: 360px;
  height: 500px;
  border-radius: 18px;
  display: flex;
  flex-direction: column;
  z-index: 9999;
  overflow: hidden;
  user-select: none;
}
.drag-handle1 { cursor: move; }
.chat-body {
  flex: 1;
  padding: 14px;
  overflow-y: auto;
  background: rgba(255,255,255,0.02);
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: auto;
}
.chat-body::-webkit-scrollbar       { width: 4px; }
.chat-body::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.10); border-radius: 4px; }
.h2 {
  font-size: 13px;
  color: rgba(255,255,255,0.7);
  font-weight: 600;
}
.msg-wrapper { display: flex; flex-direction: column; }
.msg-notice {
  align-self: center;
  margin: 6px 0;
  text-align: center;
  max-width: 90%;
}
.notice-content {
  font-size: 11px;
  color: rgba(255,255,255,0.35);
  background: rgba(255,255,255,0.05);
  padding: 3px 10px;
  border-radius: 10px;
}
.notice-time { margin-left: 5px; opacity: 0.6; font-size: 10px; }
.message {
  padding: 8px 13px;
  border-radius: 14px;
  max-width: 80%;
  font-size: 13px;
  line-height: 1.55;
  display: flex;
  flex-direction: column;
  word-wrap: break-word;
  margin-bottom: 2px;
  animation: chatAnimation 0.22s ease both;
}
.incoming {
  align-self: flex-start;
  background: rgba(255,255,255,0.08);
  color: #e8e8e8;
  border-bottom-left-radius: 4px;
}
.outgoing {
  align-self: flex-end;
  background: rgba(255,255,255,0.90);
  color: #111;
  border-bottom-right-radius: 4px;
}
.msg-text { margin: 0 0 2px; }
.msg-time {
  font-size: 10px;
  opacity: 0.45;
  align-self: flex-end;
}
.incoming .msg-time { align-self: flex-start; }
@keyframes chatAnimation {
  from { opacity: 0; transform: translateY(5px); }
  to   { opacity: 1; transform: translateY(0);   }
}

/* ================= Luna 入场遮罩 ================= */
.luna-intro-mask {
  position: fixed;
  inset: 0;
  z-index: 9000;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.luna-intro-text {
  display: flex;
  gap: 10px;
  align-items: center;
}
.luna-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: rgba(255,255,255,0.55);
  animation: lunaIntroDot 1.2s ease-in-out infinite;
}
.luna-dot:nth-child(1) { animation-delay: 0s;   }
.luna-dot:nth-child(2) { animation-delay: 0.2s; }
.luna-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes lunaIntroDot {
  0%, 80%, 100% { transform: scale(1);   opacity: 0.4; }
  40%            { transform: scale(1.5); opacity: 1;   }
}
.luna-intro-enter-active { transition: opacity 0.3s ease; }
.luna-intro-leave-active { transition: opacity 0.6s ease; }
.luna-intro-enter-from   { opacity: 0; }
.luna-intro-leave-to     { opacity: 0; }
</style>