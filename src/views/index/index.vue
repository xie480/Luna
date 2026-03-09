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
const canvasRef       = ref(null);
const wrapperRef      = ref(null);
const uiRef           = ref(null);
const contextMenuRef  = ref(null);
const messageBoxRef   = ref(null);
const historyPanelRef = ref(null);

/* ================= 基础状态 ================= */
const showDebugUI      = ref(false);
const showMessageBox   = ref(false);
const trackingEnabled  = ref(true);
const isSettingOrigin  = ref(false);
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

// 情绪图形路径（SVG path，月相/抽象符号）
const EMOTION_GLYPH_MAP = {
  neutral:   "M8 12 Q12 10 16 12",
  happy:     "M8 13 Q12 17 16 13",
  sad:       "M8 14 Q12 10 16 14",
  angry:     "M8 11 L10 13 L14 11 L16 13",
  surprised: "M12 8 Q14 12 12 16 Q10 12 12 8",
  shy:       "M9 13 Q11 15 13 13 M13 13 Q14 11 15 13",
  Solemn:    "M9 12 L15 12 M11 10 L13 14",
};
const emotionGlyphPath = computed(() => EMOTION_GLYPH_MAP[currentEmotion.value] || EMOTION_GLYPH_MAP.neutral);

// 粒子样式函数已在上方，从截断处继续补全剩余的 `<script setup>` 和 `<style>` 部分：

src\views\index\index.vue
