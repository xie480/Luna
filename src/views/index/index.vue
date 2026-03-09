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
        <!-- 情绪图标（月相风格，悬停才显示 tooltip） -->
        <div class="emotion-glyph" :class="`eg-${currentEmotion}`" :title="emotionLabel">
          <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <circle class="eg-ring" cx="12" cy="12" r="9" />
            <path class="eg-inner" :d="emotionGlyphPath" />
          </svg>
          <span class="eg-tooltip">{{ emotionLabel }}</span>
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

        <!-- 高级可爱加载动画 -->
        <div v-if="isLoading" class="luna-loader">
          <svg viewBox="0 0 36 36" class="luna-loader-svg">
            <circle class="loader-track" cx="18" cy="18" r="14" />
            <circle class="loader-arc"   cx="18" cy="18" r="14" />
          </svg>
          <div class="loader-core"></div>
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
            selected:  historyPanel.selectedDay === d,
            disabled:  !historyPanel.availableDatesSet.has(d)
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

import { useBubble }     from "../../composables/useBubble.js";
import { useAppearance } from "../../composables/useAppearance.js";
import { useRhythm }     from "../../composables/useRhythm.js";

/* ================= DOM refs ================= */
const canvasRef      = ref(null);
const wrapperRef     = ref(null);
const uiRef          = ref(null);
const contextMenuRef = ref(null);
const messageBoxRef  = ref(null);
const historyPanelRef = ref(null);

/* ================= 基础状态 ================= */
const showDebugUI     = ref(false);
const showMessageBox  = ref(false);
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

// 情绪图形路径（SVG path，月相/抽象符号，不直白）
const EMOTION_GLYPH_MAP = {
  neutral:   "M8 12 Q12 10 16 12",                          // 平直线
  happy:     "M8 13 Q12 17 16 13",                          // 上扬弧
  sad:       "M8 14 Q12 10 16 14",                          // 下垂弧
  angry:     "M8 11 L10 13 L14 11 L16 13",                  // 折线锯齿
  surprised: "M12 8 Q14 12 12 16 Q10 12 12 8",              // 水滴形
  shy:       "M9 13 Q11 15 13 13 M13 13 Q14 11 15 13",      // 双弧
  Solemn:    "M9 12 L15 12 M11 10 L13 14",                  // 十字
};
const emotionGlyphPath = computed(() => EMOTION_GLYPH_MAP[currentEmotion.value] || EMOTION_GLYPH_MAP.neutral);

// 粒子样式
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

async function onAppearanceToggleUI(file) { await onAppearanceToggle(file, getCoreModel()); }
async function applyAllEnabledUI()        { await applyAllEnabled(getCoreModel()); }
async function disableAllUI()             { await disableAll(getCoreModel()); }
async function onToggleSystemAudio()      { await toggleSystemAudio(getCoreModel(), trackingEnabled); }

/* ================= 聊天输入 ================= */
const input      = ref("");
const sending    = ref(false);
const lastReply  = ref({ loading: false, text: "" });

const isLoading = computed(() => sending.value || lastReply.value.loading);
const idlePlaceholder = "和 Luna 说点什么吧…";

let dotsTimer = null;
watch(isLoading, (loading) => {
  clearInterval(dotsTimer);
  dotsTimer = null;
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
  x: 100, y: 100,
  years: [], months: [],
  selectedYear: null, selectedMonth: null, selectedDay: null,
  availableDates: [], availableDatesSet: new Set(),
  loading: false,
});

;(function initHistoryDefaults() {
  const now = new Date();
  const cy = now.getFullYear();
  const years = [];
  for (let y = cy; y >= cy - 6; y--) years.push(y);
  historyPanel.value.years  = years;
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
  historyPanel.value.loading    = true;
  historyPanel.value.selectedDay = null;
  try {
    const yearMonth = `${year}:${String(month).padStart(2, "0")}`;
    const arr  = await historyDateApi(yearMonth);
    const days = (arr || []).map(Number).filter((d) => !isNaN(d));
    historyPanel.value.availableDates    = [...days];
    historyPanel.value.availableDatesSet = new Set(days);
  } catch (e) {
    console.error("[History] fetchHistoryForMonth 失败", e);
  } finally {
    historyPanel.value.loading = false;
  }
}

async function changeMonth(delta) {
  const now = new Date();
  if (!historyPanel.value.selectedYear)  historyPanel.value.selectedYear  = now.getFullYear();
  if (!historyPanel.value.selectedMonth) historyPanel.value.selectedMonth = 1;
  let y = historyPanel.value.selectedYear;
  let m = historyPanel.value.selectedMonth + delta;
  if (m < 1)  { m = 12; y--; }
  else if (m > 12) { m = 1; y++; }
  historyPanel.value.selectedYear  = y;
  historyPanel.value.selectedMonth = m;
  if (!historyPanel.value.years.includes(y)) {
    historyPanel.value.years.push(y);
    historyPanel.value.years.sort((a, b) => b - a);
  }
  await fetchHistoryForMonth(y, m);
}

function onYearChange()  { fetchHistoryForMonth(historyPanel.value.selectedYear,  historyPanel.value.selectedMonth); }
function onMonthChange() { fetchHistoryForMonth(historyPanel.value.selectedYear,  historyPanel.value.selectedMonth); }

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
let historyDragStart  = { x: 0, y: 0 };
let historyPanelStart = { x: 0, y: 0 };

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
  historyPanel.value.x = Math.min(window.innerWidth  - 320, Math.max(0, historyPanelStart.x + e.clientX - historyDragStart.x));
  historyPanel.value.y = Math.min(window.innerHeight - 300, Math.max(0, historyPanelStart.y + e.clientY - historyDragStart.y));
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

let isDragging = false, startX = 0, startY = 0;

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
  if (contextMenu.value.visible && contextMenuRef.value && !contextMenuRef.value.contains(e.target))
    contextMenu.value.visible = false;
  if (historyPanel.value.visible && historyPanelRef.value && !historyPanelRef.value.contains(e.target))
    historyPanel.value.visible = false;
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
  appearancePanel.value.x = Math.min(window.innerWidth  - 420, Math.max(0, panelStart.x + e.clientX - dragStart.x));
  appearancePanel.value.y = Math.min(window.innerHeight - 360, Math.max(0, panelStart.y + e.clientY - dragStart.y));
}
function onAppearanceDragEnd() {
  draggingAppearance = false;
  document.removeEventListener("pointermove", onAppearanceDragMove);
  document.removeEventListener("pointerup",   onAppearanceDragEnd);
  updatePetState();
}

function openAppearancePanelAt(x, y) {
  const pw = 420, ph = 360;
  appearancePanel.value.x = x + pw > window.innerWidth  ? window.innerWidth  - pw - 10 : x;
  appearancePanel.value.y = y + ph > window.innerHeight ? window.innerHeight - ph - 10 : y;
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

/* ================= 拖拽模型 ================= */
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

/* ================= 滚轮缩放 ================= */
function onWheel(ev) {
  const rect        = canvasRef.value.getBoundingClientRect();
  const globalPoint = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  if (!isPointInsideModel(globalPoint)) return;
  ev.preventDefault();
  const factor     = ev.deltaY > 0 ? 0.95 : 1.05;
  const newScale   = Math.min(10, Math.max(0.05, (container.scale.x || 1) * factor));
  const localPoint = container.toLocal(globalPoint, app.stage);
  container.scale.set(newScale);
  const newGlobal  = container.toGlobal(localPoint);
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
    core.setParameterValueById(PARAM_CONFIG.EYE_X.param,  mapRange(nx, PARAM_CONFIG.EYE_X.range));
    core.setParameterValueById(PARAM_CONFIG.EYE_Y.param,  mapRange(ny, PARAM_CONFIG.EYE_Y.range));
    core.setParameterValueById(PARAM_CONFIG.HEAD_X.param, mapRange(nx, PARAM_CONFIG.HEAD_X.range));
    core.setParameterValueById(PARAM_CONFIG.HEAD_Y.param, mapRange(ny, PARAM_CONFIG.HEAD_Y.range));
  } catch {}
}

