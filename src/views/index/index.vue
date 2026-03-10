<template>
  <div class="app-root">

    <!-- ===== 背景装饰粒子（仅启动阶段使用） ===== -->
    <div v-if="bgParticlesVisible" class="bg-particles">
      <span v-for="i in 18" :key="i" class="particle" :style="particleStyle(i)"></span>
    </div>

    <!-- ===== 全局 CRT 扫描纹理 ===== -->
    <div class="crt-overlay" aria-hidden="true"></div>

    <!-- ===== 调试 UI ===== -->
    <div
      v-if="showDebugUI"
      ref="uiRef"
      class="debug-ui"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <div class="debug-topbar">
        <span class="debug-topbar-dot"></span>
        <span class="debug-topbar-label">SYS_CTRL</span>
        <span class="debug-topbar-line"></span>
      </div>
      <div class="debug-btns">
        <button class="cute-btn" @click="toggleTracking">
          <span class="btn-dot" :class="trackingEnabled ? 'green' : 'red'"></span>
          <span class="btn-code-prefix">&gt;_</span>
          {{ trackingEnabled ? "视线追踪 ON" : "视线追踪 OFF" }}
        </button>
        <button class="cute-btn" @click="startSetOrigin">
          <span class="btn-code-prefix">&gt;_</span>
          {{ isSettingOrigin ? "点击画布设置..." : "设置原点" }}
        </button>
        <button class="cute-btn" @click="clearOrigin">
          <span class="btn-code-prefix">&gt;_</span>
          清除原点
        </button>
      </div>
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
        <div class="msgbox-glow-border" aria-hidden="true"></div>
        <div class="msgbox-scanline" aria-hidden="true"></div>
        <span class="msgbox-corner tl" aria-hidden="true"></span>
        <span class="msgbox-corner tr" aria-hidden="true"></span>
        <span class="msgbox-corner bl" aria-hidden="true"></span>
        <span class="msgbox-corner br" aria-hidden="true"></span>

        <div class="breath-light" :class="`bl-${currentEmotion}`">
          <div class="bl-core"></div>
          <span class="bl-tooltip">{{ emotionLabel }}</span>
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
        <div class="cm-scanbar" aria-hidden="true"></div>
        <span class="cm-corner tl" aria-hidden="true"></span>
        <span class="cm-corner tr" aria-hidden="true"></span>
        <span class="cm-corner bl" aria-hidden="true"></span>
        <span class="cm-corner br" aria-hidden="true"></span>

        <div class="menu-header">
          <span class="menu-logo-bracket">[</span>
          <span class="menu-logo">LUNA</span>
          <span class="menu-logo-bracket">]</span>
          <span class="menu-logo-ver">v2.0.1</span>
          <span class="menu-header-status" aria-hidden="true"></span>
        </div>
        <div class="menu-divider-solid"></div>

        <div class="menu-item" @click="toggleDebugUI" style="--i:0">
          <span class="mi-icon">{{ showDebugUI ? '🔴' : '🟢' }}</span>
          <span class="mi-text">{{ showDebugUI ? "隐藏调试UI" : "显示调试UI" }}</span>
          <span class="mi-arrow">›</span>
        </div>
        <div class="menu-item" @click="toggleMessageBox" style="--i:1">
          <span class="mi-icon">💬</span>
          <span class="mi-text">{{ showMessageBox ? "隐藏输入框" : "显示输入框" }}</span>
          <span class="mi-arrow">›</span>
        </div>
        <div class="menu-item" @click="openAppearancePanelAt(contextMenu.x, contextMenu.y)" style="--i:2">
          <span class="mi-icon">✨</span>
          <span class="mi-text">外貌设置</span>
          <span class="mi-arrow">›</span>
        </div>
        <div class="menu-item" @click="resetModelState" style="--i:3">
          <span class="mi-icon">🔄</span>
          <span class="mi-text">重置模型表情</span>
          <span class="mi-arrow">›</span>
        </div>
        <div class="menu-item" @click="onToggleSystemAudio" style="--i:4">
          <span class="mi-icon">{{ rhythmShowListening ? '🔇' : '🎵' }}</span>
          <span class="mi-text">{{ rhythmShowListening ? "关闭音频律动" : "开启音频律动" }}</span>
          <span class="mi-arrow">›</span>
        </div>
        <div class="menu-item" @click="openHistoryPanelAt(contextMenu.x, contextMenu.y)" style="--i:5">
          <span class="mi-icon">📖</span>
          <span class="mi-text">历史记录</span>
          <span class="mi-arrow">›</span>
        </div>
        <div class="menu-divider"></div>
        <div class="menu-item danger" @click="closeLuna" style="--i:6">
          <span class="mi-icon">💔</span>
          <span class="mi-text">关闭 Luna</span>
          <span class="mi-arrow">›</span>
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
      <div class="panel-topbar" aria-hidden="true"></div>
      <div class="panel-header drag-handle" @pointerdown="onAppearanceDragStart">
        <span class="panel-header-icon">✨</span>
        <span class="panel-header-title">外貌设置</span>
        <span class="panel-header-id">MOD_APPEARANCE</span>
        <button class="close-x" @click="closeAppearancePanel">×</button>
      </div>
      <div class="panel-body">
        <div class="appearance-controls">
          <div class="appearance-item" v-for="file in APPEARANCE_FILES" :key="file">
            <label class="checkbox-label">
              <input type="checkbox" v-model="appearanceEnabled[file]" @change="onAppearanceToggleUI(file)" />
              <span class="checkbox-custom"></span>
              <span class="file-name">{{ displayAppearanceName(file) }}</span>
            </label>
          </div>
        </div>
      </div>
      <div class="panel-footer">
        <button class="cute-btn small" @click="applyAllEnabledUI">
          <span class="btn-code-prefix">&gt;_</span>应用已启用
        </button>
        <button class="cute-btn small danger" @click="disableAllUI">
          <span class="btn-code-prefix">&gt;_</span>全部禁用
        </button>
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
      <div class="panel-topbar" aria-hidden="true"></div>
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
        <span class="panel-header-id">HIST_LOG</span>
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
      <div class="panel-topbar" aria-hidden="true"></div>
      <div class="panel-header drag-handle1" @mousedown="startDrag">
        <span class="panel-header-icon">📖</span>
        <span class="h2">{{ selectedHistoryDate }}</span>
        <span class="panel-header-id">CHAT_RECORD</span>
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
        <div class="luna-boot-screen">
          <div class="scan-line"></div>
          <span class="boot-corner btl" aria-hidden="true"></span>
          <span class="boot-corner btr" aria-hidden="true"></span>
          <span class="boot-corner bbl" aria-hidden="true"></span>
          <span class="boot-corner bbr" aria-hidden="true"></span>
          <div class="boot-title">
            <span class="boot-bracket">[</span>
            <span class="boot-name">LUNA</span>
            <span class="boot-bracket">]</span>
            <span class="boot-version">v2.0.1</span>
          </div>
          <div class="boot-subtitle">AI ASSISTANT CORE — INITIALIZING</div>
          <div class="boot-bar-wrap">
            <div class="boot-bar-track">
              <div class="boot-bar-fill"></div>
            </div>
            <span class="boot-bar-pct">LOADING...</span>
          </div>
          <div class="boot-log">
            <div class="log-line" v-for="(line, i) in bootLines" :key="i"
              :style="{ animationDelay: i * 0.18 + 's' }">
              <span class="log-tag">&gt;</span> {{ line }}
            </div>
          </div>
          <div class="boot-footer">
            <span class="boot-hex" v-for="h in 6" :key="h">{{ hexChars[h-1] }}</span>
          </div>
        </div>
      </div>
    </transition>

    <!-- ===== 代码粒子入场动画 ===== -->
    <canvas
      v-if="codeParticleVisible"
      ref="particleCanvasRef"
      class="particle-canvas-overlay"
    ></canvas>

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
const bgParticlesVisible  = ref(true);
const showDebugUI         = ref(false);
const showMessageBox      = ref(false);
const trackingEnabled     = ref(true);
const isSettingOrigin     = ref(false);
const lunaIntroVisible    = ref(true);
const codeParticleVisible = ref(false);
const particleCanvasRef   = ref(null);

function runCodeParticleIntro(onDone) {
  codeParticleVisible.value = true;

  nextTick(() => {
    const canvas = particleCanvasRef.value;
    if (!canvas) { onDone?.(); return; }

    const W = canvas.width  = window.innerWidth;
    const H = canvas.height = window.innerHeight;
    const ctx = canvas.getContext("2d");

    const baseGrad = ctx.createLinearGradient(0, 0, 0, H);
    baseGrad.addColorStop(0,   "#020611");
    baseGrad.addColorStop(0.5, "#050b18");
    baseGrad.addColorStop(1,   "#020611");
    ctx.fillStyle = baseGrad;
    ctx.fillRect(0, 0, W, H);

    const CX    = W / 2;
    const CY    = H / 2 + 60;
    const SCALE = Math.min(W, H) / 560;

    function buildSilhouette() {
      const pts = [];

      function circle(cx, cy, rx, ry, step = 0.10) {
        for (let a = 0; a < Math.PI * 2; a += step)
          pts.push({ x: cx + Math.cos(a) * rx, y: cy + Math.sin(a) * ry });
      }
      function arc(cx, cy, rx, ry, a0, a1, step = 0.10) {
        for (let a = a0; a <= a1; a += step)
          pts.push({ x: cx + Math.cos(a) * rx, y: cy + Math.sin(a) * ry });
      }
      function line(x0, y0, x1, y1, n) {
        for (let i = 0; i <= n; i++) {
          const t = i / n;
          pts.push({ x: x0 + (x1 - x0) * t, y: y0 + (y1 - y0) * t });
        }
      }
      function fill(x0, y0, x1, y1, cols, rows) {
        for (let r = 0; r <= rows; r++)
          for (let c = 0; c <= cols; c++)
            pts.push({
              x: x0 + (x1 - x0) * (c / cols),
              y: y0 + (y1 - y0) * (r / rows),
            });
      }
      function scatter(cx, cy, rx, ry, n) {
        for (let i = 0; i < n; i++)
          pts.push({
            x: cx + (Math.random() - 0.5) * rx * 2,
            y: cy + (Math.random() - 0.5) * ry * 2,
          });
      }

      const s = SCALE;

      circle(CX, CY, 130 * s, 115 * s, 0.05);
      circle(CX, CY, 115 * s, 100 * s, 0.07);
      circle(CX, CY,  95 * s,  82 * s, 0.10);
      fill(CX - 100 * s, CY - 80 * s, CX + 100 * s, CY + 80 * s, 14, 12);

      line(CX - 130 * s, CY - 80  * s, CX - 80 * s, CY - 185 * s, 14);
      line(CX - 80  * s, CY - 185 * s, CX - 38 * s, CY -  95 * s, 14);
      line(CX - 118 * s, CY -  90 * s, CX - 82 * s, CY - 165 * s, 10);
      line(CX - 82  * s, CY - 165 * s, CX - 50 * s, CY - 102 * s, 10);
      fill(CX - 112 * s, CY - 158 * s, CX - 52 * s, CY - 102 * s, 5, 5);

      line(CX + 130 * s, CY -  80 * s, CX + 80 * s, CY - 185 * s, 14);
      line(CX + 80  * s, CY - 185 * s, CX + 38 * s, CY -  95 * s, 14);
      line(CX + 118 * s, CY -  90 * s, CX + 82 * s, CY - 165 * s, 10);
      line(CX + 82  * s, CY - 165 * s, CX + 50 * s, CY - 102 * s, 10);
      fill(CX + 52  * s, CY - 158 * s, CX + 112 * s, CY - 102 * s, 5, 5);

      circle(CX - 50 * s, CY - 15 * s, 30 * s, 22 * s, 0.10);
      circle(CX - 50 * s, CY - 15 * s, 20 * s, 14 * s, 0.14);
      circle(CX - 50 * s, CY - 15 * s,  9 * s, 15 * s, 0.18);
      scatter(CX - 40 * s, CY - 24 * s, 6 * s, 6 * s, 8);
      arc(CX - 50 * s, CY - 15 * s, 32 * s, 24 * s, Math.PI * 1.18, Math.PI * 1.88, 0.08);
      for (let i = 0; i < 7; i++) {
        const a  = Math.PI * 1.22 + i * 0.10;
        const bx = CX - 50 * s + Math.cos(a) * 32 * s;
        const by = CY - 15  * s + Math.sin(a) * 24 * s;
        line(bx, by, bx + Math.cos(a) * 12 * s, by + Math.sin(a) * 12 * s, 4);
      }

      circle(CX + 50 * s, CY - 15 * s, 30 * s, 22 * s, 0.10);
      circle(CX + 50 * s, CY - 15 * s, 20 * s, 14 * s, 0.14);
      circle(CX + 50 * s, CY - 15 * s,  9 * s, 15 * s, 0.18);
      scatter(CX + 60 * s, CY - 24 * s, 6 * s, 6 * s, 8);
      arc(CX + 50 * s, CY - 15 * s, 32 * s, 24 * s, Math.PI * 1.12, Math.PI * 1.82, 0.08);
      for (let i = 0; i < 7; i++) {
        const a  = Math.PI * 1.12 + i * 0.10;
        const bx = CX + 50 * s + Math.cos(Math.PI * 2 - a) * 32 * s;
        const by = CY - 15  * s + Math.sin(a) * 24 * s;
        line(bx, by, bx - Math.cos(a) * 12 * s, by + Math.sin(a) * 12 * s, 4);
      }

      line(CX - 12 * s, CY + 25 * s, CX + 12 * s, CY + 25 * s, 5);
      line(CX - 12 * s, CY + 25 * s, CX,           CY + 40 * s, 5);
      line(CX + 12 * s, CY + 25 * s, CX,           CY + 40 * s, 5);

      arc(CX - 24 * s, CY + 55 * s, 20 * s, 11 * s, Math.PI * 1.5, Math.PI * 2.0, 0.10);
      arc(CX + 24 * s, CY + 55 * s, 20 * s, 11 * s, Math.PI,       Math.PI * 1.5, 0.10);

      line(CX - 38 * s, CY + 28 * s, CX - 125 * s, CY + 18 * s, 10);
      line(CX - 38 * s, CY + 35 * s, CX - 128 * s, CY + 34 * s, 10);
      line(CX - 38 * s, CY + 42 * s, CX - 125 * s, CY + 50 * s, 10);
      line(CX - 38 * s, CY + 22 * s, CX - 118 * s, CY +  5 * s, 10);
      line(CX + 38 * s, CY + 28 * s, CX + 125 * s, CY + 18 * s, 10);
      line(CX + 38 * s, CY + 35 * s, CX + 128 * s, CY + 34 * s, 10);
      line(CX + 38 * s, CY + 42 * s, CX + 125 * s, CY + 50 * s, 10);
      line(CX + 38 * s, CY + 22 * s, CX + 118 * s, CY +  5 * s, 10);

      scatter(CX - 85 * s, CY + 22 * s, 26 * s, 14 * s, 24);
      scatter(CX + 85 * s, CY + 22 * s, 26 * s, 14 * s, 24);

      for (let a = 0; a < Math.PI * 2; a += 0.12)
        pts.push({
          x: CX - 65 * s + Math.cos(a) * 48 * s * (1 + 0.3 * Math.cos(2 * a)),
          y: CY - 238 * s + Math.sin(a) * 26 * s * (1 + 0.3 * Math.cos(2 * a)),
        });
      for (let a = 0; a < Math.PI * 2; a += 0.12)
        pts.push({
          x: CX + 65 * s + Math.cos(a) * 48 * s * (1 + 0.3 * Math.cos(2 * a)),
          y: CY - 238 * s + Math.sin(a) * 26 * s * (1 + 0.3 * Math.cos(2 * a)),
        });
      circle(CX, CY - 238 * s, 12 * s, 12 * s, 0.22);
      line(CX - 10 * s, CY - 226 * s, CX - 22 * s, CY - 200 * s, 6);
      line(CX + 10 * s, CY - 226 * s, CX + 22 * s, CY - 200 * s, 6);

      line(CX, CY + 118 * s, CX - 62 * s, CY + 152 * s, 12);
      line(CX, CY + 118 * s, CX + 62 * s, CY + 152 * s, 12);
      line(CX - 16 * s, CY + 118 * s, CX, CY + 130 * s, 6);
      line(CX + 16 * s, CY + 118 * s, CX, CY + 130 * s, 6);
      circle(CX, CY + 130 * s, 6 * s, 6 * s, 0.30);

      arc(CX - 140 * s, CY + 105 * s, 34 * s, 22 * s, Math.PI * 1.4, Math.PI * 2.0, 0.10);
      arc(CX + 140 * s, CY + 105 * s, 34 * s, 22 * s, Math.PI,       Math.PI * 1.6, 0.10);
      line(CX - 140 * s, CY + 105 * s, CX - 90 * s, CY + 82 * s, 10);
      line(CX + 140 * s, CY + 105 * s, CX + 90 * s, CY + 82 * s, 10);

      const petalPos = [
        [CX - 230 * s, CY - 180 * s],
        [CX + 220 * s, CY - 150 * s],
        [CX - 250 * s, CY +  50 * s],
        [CX + 240 * s, CY +  70 * s],
        [CX,           CY - 310 * s],
        [CX - 180 * s, CY + 150 * s],
        [CX + 185 * s, CY + 145 * s],
        [CX - 60  * s, CY - 295 * s],
      ];
      petalPos.forEach(([px, py]) => {
        for (let a = 0; a < Math.PI * 2; a += 0.16)
          pts.push({
            x: px + Math.cos(a) * 20 * s * (1 + 0.5 * Math.cos(a)),
            y: py + Math.sin(a) * 13 * s,
          });
      });

      const notePositions = [
        [CX + 250 * s, CY -  70 * s],
        [CX - 262 * s, CY +   0 * s],
        [CX + 200 * s, CY + 160 * s],
        [CX - 210 * s, CY + 155 * s],
      ];
      notePositions.forEach(([nx, ny]) => {
        circle(nx, ny, 9 * s, 7 * s, 0.28);
        line(nx + 9 * s, ny, nx + 9 * s, ny - 28 * s, 6);
        line(nx + 9 * s, ny - 28 * s, nx + 20 * s, ny - 20 * s, 3);
      });

      const starPos = [
        [CX - 200 * s, CY - 110 * s],
        [CX + 195 * s, CY - 120 * s],
        [CX - 190 * s, CY + 120 * s],
        [CX + 192 * s, CY + 125 * s],
        [CX - 80  * s, CY - 280 * s],
        [CX + 85  * s, CY - 275 * s],
      ];
      starPos.forEach(([sx, sy]) => {
        for (let i = 0; i < 5; i++) {
          const a0 = (i / 5) * Math.PI * 2 - Math.PI / 2;
          const a1 = a0 + Math.PI / 5;
          const R  = 14 * s, r = 6 * s;
          line(
            sx + Math.cos(a0) * R, sy + Math.sin(a0) * R,
            sx + Math.cos(a1) * r, sy + Math.sin(a1) * r, 2
          );
          line(
            sx + Math.cos(a1) * r, sy + Math.sin(a1) * r,
            sx + Math.cos(a0 + Math.PI * 2 / 5) * R,
            sy + Math.sin(a0 + Math.PI * 2 / 5) * R, 2
          );
        }
      });

      [[CX - 160 * s, CY - 200 * s], [CX + 158 * s, CY - 195 * s]].forEach(([hx, hy]) => {
        for (let a = 0; a < Math.PI * 2; a += 0.18) {
          pts.push({
            x: hx + 12 * s * (16 * Math.pow(Math.sin(a), 3)) / 16,
            y: hy - 12 * s * (13 * Math.cos(a) - 5 * Math.cos(2*a) - 2 * Math.cos(3*a) - Math.cos(4*a)) / 16,
          });
        }
      });

      return pts;
    }

    const silhouette = buildSilhouette();

    const CHAR_SETS = [
      "01",
      "ABCDEF0123456789",
      "アイウエオカキクケコサシスセソタチツ",
      "{}[]()<>|/\\=+-*&^%$#@!~",
      "λΣΩΔΨΦπμσ♡♪★☆◇◆",
      "░▒▓█▄▀■□",
    ];

    function randChar() {
      const set = CHAR_SETS[Math.floor(Math.random() * CHAR_SETS.length)];
      return set[Math.floor(Math.random() * set.length)];
    }

    const TOTAL = Math.min(silhouette.length, 700);
    const chosen = silhouette.sort(() => Math.random() - 0.5).slice(0, TOTAL);

    const COLORS = [
      [255, 180, 210], [255, 140, 200], [200, 160, 255],
      [0, 220, 255], [255, 255, 255], [255, 200, 230],
      [255, 120, 180], [180, 100, 255],
    ];

    const particles = chosen.map((target) => {
      const col  = COLORS[Math.floor(Math.random() * COLORS.length)];
      const edge = Math.floor(Math.random() * 4);
      let sx, sy;
      if      (edge === 0) { sx = Math.random() * W; sy = -30; }
      else if (edge === 1) { sx = W + 30;             sy = Math.random() * H; }
      else if (edge === 2) { sx = Math.random() * W; sy = H + 30; }
      else                 { sx = -30;                sy = Math.random() * H; }
      return {
        x: sx, y: sy, tx: target.x, ty: target.y,
        char: randChar(), size: 8 + Math.random() * 7,
        speed: 0.050 + Math.random() * 0.050, alpha: 0, col,
        phase: Math.random() * Math.PI * 2, charTimer: 0,
        charInterval: 3 + Math.floor(Math.random() * 6),
        trail: [], trailMax: 5 + Math.floor(Math.random() * 6),
      };
    });

    const RAIN_COLS = 34;
    const rainDrops = Array.from({ length: RAIN_COLS }, (_, i) => ({
      x:     (i / RAIN_COLS) * W + Math.random() * (W / RAIN_COLS),
      y:     Math.random() * H,
      speed: 1.6 + Math.random() * 2.8,
      chars: Array.from({ length: 20 }, () => randChar()),
      alpha: 0.07 + Math.random() * 0.10,
      gap:   16 + Math.random() * 8,
    }));

    let frame = 0;
    const GATHER  = 90;
    const HOLD    = 70;
    const FADEOUT = 80;
    const TOTAL_F = GATHER + HOLD + FADEOUT;
    let rafId;

    function tick() {
      ctx.clearRect(0, 0, W, H);
      ctx.fillStyle = "rgba(0,0,0,0.28)";
      ctx.fillRect(0, 0, W, H);

      const prog_gather = Math.min(1, frame / GATHER);
      const rainAlpha = frame < GATHER
        ? prog_gather * 0.65
        : frame < GATHER + HOLD
          ? 0.65
          : Math.max(0, 0.65 - ((frame - GATHER - HOLD) / FADEOUT) * 1.5);

      rainDrops.forEach((drop) => {
        drop.y += drop.speed;
        if (drop.y > H + drop.chars.length * drop.gap) drop.y = -drop.chars.length * drop.gap;
        if (Math.random() < 0.04) drop.chars[Math.floor(Math.random() * drop.chars.length)] = randChar();
        drop.chars.forEach((ch, i) => {
          const fy = drop.y + i * drop.gap;
          if (fy < 0 || fy > H) return;
          const fade = 1 - i / drop.chars.length;
          ctx.save();
          ctx.globalAlpha = drop.alpha * fade * rainAlpha;
          ctx.font        = `${11 + i * 0.3}px "Courier New", monospace`;
          ctx.fillStyle   = i === 0 ? "#ffffff" : "rgba(255,180,220,1)";
          ctx.shadowColor = "rgba(255,160,210,0.6)";
          ctx.shadowBlur  = i === 0 ? 10 : 4;
          ctx.fillText(ch, drop.x, fy);
          ctx.restore();
        });
      });

      particles.forEach((p) => {
        if (frame < GATHER) {
          p.x    += (p.tx - p.x) * p.speed;
          p.y    += (p.ty - p.y) * p.speed;
          const t = frame / GATHER;
          p.alpha = Math.min(1, t * t * 2.5);
        } else if (frame < GATHER + HOLD) {
          p.x     = p.tx + Math.sin(frame * 0.07 + p.phase) * 1.4 * SCALE;
          p.y     = p.ty + Math.cos(frame * 0.05 + p.phase) * 1.4 * SCALE;
          p.alpha = 1;
          p.charTimer++;
          if (p.charTimer >= p.charInterval) { p.char = randChar(); p.charTimer = 0; }
        } else {
          const t      = (frame - GATHER - HOLD) / FADEOUT;
          const jitter = (1 - t) * 2.5 * SCALE;
          p.x     = p.tx + (Math.random() - 0.5) * jitter;
          p.y     = p.ty + (Math.random() - 0.5) * jitter;
          p.alpha = t < 0.25 ? 1 : Math.max(0, 1 - (t - 0.25) / 0.75);
          p.alpha = Math.pow(p.alpha, 1.8);
          if (Math.random() < t * 0.4) p.char = randChar();
        }

        if (p.alpha <= 0.01) return;

        p.trail.push({ x: p.x, y: p.y });
        if (p.trail.length > p.trailMax) p.trail.shift();
        p.trail.forEach((tp, i) => {
          const ta = (i / p.trail.length) * p.alpha * 0.32;
          if (ta < 0.02) return;
          ctx.save();
          ctx.globalAlpha = ta;
          ctx.font        = `${p.size * 0.7}px "Courier New", monospace`;
          ctx.fillStyle   = `rgba(${p.col[0]},${p.col[1]},${p.col[2]},1)`;
          ctx.fillText(p.char, tp.x, tp.y);
          ctx.restore();
        });

        ctx.save();
        ctx.globalAlpha = p.alpha;
        ctx.font        = `bold ${p.size}px "Courier New", monospace`;
        ctx.fillStyle   = `rgba(${p.col[0]},${p.col[1]},${p.col[2]},1)`;
        ctx.shadowColor = `rgba(${p.col[0]},${p.col[1]},${p.col[2]},0.9)`;
        ctx.shadowBlur  = 14;
        ctx.fillText(p.char, p.x, p.y);
        ctx.restore();
      });

      if (frame >= GATHER * 0.5 && frame < GATHER + HOLD) {
        const prog    = Math.min(1, (frame - GATHER * 0.5) / (GATHER * 0.5));
        const fadeOut = frame >= GATHER + HOLD * 0.6 ? 1 - (frame - GATHER - HOLD * 0.6) / (HOLD * 0.4) : 1;
        const radius  = 280 * SCALE * prog;
        const grd     = ctx.createRadialGradient(CX, CY, 0, CX, CY, radius);
        grd.addColorStop(0,   `rgba(255,160,220,${0.09 * prog * fadeOut})`);
        grd.addColorStop(0.4, `rgba(180,120,255,${0.05 * prog * fadeOut})`);
        grd.addColorStop(1,   "rgba(0,0,0,0)");
        ctx.fillStyle = grd;
        ctx.fillRect(0, 0, W, H);
      }

      if (frame >= GATHER && frame < GATHER + HOLD) {
        const t     = (frame - GATHER) / HOLD;
        const scanY = (CY - 280 * SCALE) + t * 460 * SCALE;
        const sGrd  = ctx.createLinearGradient(0, scanY - 16, 0, scanY + 16);
        sGrd.addColorStop(0,   "rgba(255,180,220,0)");
        sGrd.addColorStop(0.5, `rgba(255,180,220,${0.22 * Math.sin(t * Math.PI)})`);
        sGrd.addColorStop(1,   "rgba(255,180,220,0)");
        ctx.fillStyle = sGrd;
        ctx.fillRect(CX - 200 * SCALE, scanY - 16, 400 * SCALE, 32);
      }

      if (frame >= GATHER + HOLD) {
        const t = (frame - GATHER - HOLD) / FADEOUT;
        if (t < 0.35) {
          const ringT  = t / 0.35;
          const eR     = ringT * ringT;
          const r1     = 20 * SCALE + eR * Math.max(W, H) * 0.55;
          const ring1A = (1 - ringT) * 0.55;
          const grd1   = ctx.createRadialGradient(CX, CY, r1 * 0.82, CX, CY, r1);
          grd1.addColorStop(0,    `rgba(255,160,220,0)`);
          grd1.addColorStop(0.45, `rgba(255,180,230,${ring1A})`);
          grd1.addColorStop(0.75, `rgba(180,120,255,${ring1A * 0.6})`);
          grd1.addColorStop(1,    `rgba(0,0,0,0)`);
          ctx.fillStyle = grd1;
          ctx.fillRect(0, 0, W, H);
        }
        if (t > 0.15 && t < 0.75) {
          const lt    = (t - 0.15) / 0.60;
          const lEase = lt < 0.5 ? 2 * lt * lt : 1 - Math.pow(-2 * lt + 2, 2) / 2;
          const coreA = lEase < 0.5 ? lEase * 2 : (1 - lEase) * 2;
          const coreR = 18 * SCALE * (0.5 + lEase * 0.8);
          const cGrd  = ctx.createRadialGradient(CX, CY, 0, CX, CY, coreR);
          cGrd.addColorStop(0,   `rgba(255,255,255,${coreA * 0.85})`);
          cGrd.addColorStop(0.3, `rgba(220,180,255,${coreA * 0.45})`);
          cGrd.addColorStop(1,   `rgba(0,0,0,0)`);
          ctx.fillStyle = cGrd;
          ctx.fillRect(0, 0, W, H);
        }
        if (t > 0.70) {
          const fadeT = (t - 0.70) / 0.30;
          ctx.fillStyle = `rgba(2,6,17,${fadeT * fadeT * 0.96})`;
          ctx.fillRect(0, 0, W, H);
        }
      }

      if (frame < GATHER + HOLD + FADEOUT * 0.3) {
        const hudAlpha = frame < GATHER + HOLD
          ? Math.min(1, frame / 20)
          : Math.max(0, 1 - (frame - GATHER - HOLD) / (FADEOUT * 0.3));
        ctx.save();
        ctx.globalAlpha = hudAlpha * 0.32;
        ctx.font        = '10px "Courier New", monospace';
        ctx.fillStyle   = "rgba(255,180,220,1)";
        ctx.fillText(`FRAME  : ${String(frame).padStart(4, "0")}`, 24, H - 60);
        ctx.fillText(`POINTS : ${TOTAL}`,                          24, H - 46);
        ctx.fillText(`STATUS : ${frame < GATHER ? "ASSEMBLING" : frame < GATHER + HOLD ? "LOCKED" : "DISSOLVING"}`, 24, H - 32);
        ctx.fillText(`SYS    : LUNA-CORE v2.0.1`,                 24, H - 18);
        ctx.textAlign = "right";
        ctx.fillText(`RES ${W}x${H}`,         W - 24, H - 46);
        ctx.fillText(`ENTITY : NEKO-JK MODE`, W - 24, H - 32);
        ctx.fillText(`AI ASSISTANT ONLINE`,   W - 24, H - 18);
        ctx.restore();
      }

      frame++;
      if (frame < TOTAL_F) {
        rafId = requestAnimationFrame(tick);
      } else {
        ctx.clearRect(0, 0, W, H);
        codeParticleVisible.value = false;
        onDone?.();
      }
    }

    tick();
  });
}

const bootLines = [
  "Initializing neural interface...",
  "Loading Live2D core module...",
  "Mounting expression engine...",
  "Calibrating gaze tracking...",
  "Connecting to Luna API...",
  "Warming up language model...",
  "System ready.",
];

const hexChars = ref([]);
function genHex() {
  hexChars.value = Array.from({ length: 6 }, () =>
    Math.floor(Math.random() * 0xFFFF).toString(16).toUpperCase().padStart(4, "0")
  );
}
genHex();
let hexTimer = null;
onMounted(() => { hexTimer = setInterval(genHex, 180); });
onBeforeUnmount(() => { clearInterval(hexTimer); });

const currentEmotion = ref("neutral");
const EMOTION_LABEL_MAP = {
  Angry: "愤怒", Annoyed: "烦躁", Irritated: "不耐烦", Frustrated: "沮丧", Determined: "坚定",
  Sad: "难过", Lonely: "孤独", Despair: "绝望", Broken: "心碎", Uneasy: "不安",
  Resigned: "无奈", Disappointed: "失望", Anxious: "焦虑", Fearful: "恐惧", Shocked: "震惊",
  Tired: "疲惫", Bored: "无聊", Confused: "困惑", Embarrassed: "尴尬", Flustered: "慌乱",
  Affectionate: "温柔", Clingy: "黏人", Shy: "害羞", Soft: "柔软", Tsundere: "傲娇",
  Grateful: "感激", Relieved: "释然", Teasing: "戏弄", Yandere: "病娇",
  Smile: "微笑", Hopeful: "期待", Proud: "骄傲", Solemn: "庄重", neutral: "平静",
  happy: "开心", sad: "难过", angry: "生气", surprised: "惊讶", shy: "害羞",
};
const emotionLabel = computed(() => EMOTION_LABEL_MAP[currentEmotion.value] || currentEmotion.value);

function particleStyle(i) {
  const size  = 4 + (i % 5) * 3;
  const left  = (i * 37 + 11) % 100;
  const top   = (i * 53 + 7)  % 100;
  const delay = (i * 0.4) % 3;
  const dur   = 3 + (i % 4);
  return {
    width: `${size}px`, height: `${size}px`,
    left: `${left}%`, top: `${top}%`,
    animationDelay: `${delay}s`, animationDuration: `${dur}s`,
  };
}

let app       = null;
let container = null;
let model     = null;
const expressionCache = new Map();

const { chatBubbles, bubbleAnchor, registerBubble, sendReplyAsBubbles } = useBubble(messageBoxRef, showMessageBox);

const {
  APPEARANCE_FILES, appearanceEnabled, appearanceAppliedMeta,
  appearanceHint, showAppearanceHint, displayAppearanceName,
  loadAppearanceState, saveAppearanceState, applyAppearanceFile,
  removeAppearanceFile, applyAllEnabled, disableAll, onAppearanceToggle,
} = useAppearance();

const { showSystemAudioListening: rhythmShowListening, toggleSystemAudio, dispose: disposeRhythm } = useRhythm();

function getCoreModel() { return model?.internalModel?.coreModel ?? null; }

async function onAppearanceToggleUI(file) { await onAppearanceToggle(file, getCoreModel()); }
async function applyAllEnabledUI()        { await applyAllEnabled(getCoreModel()); }
async function disableAllUI()             { await disableAll(getCoreModel()); }
async function onToggleSystemAudio()      { await toggleSystemAudio(getCoreModel(), trackingEnabled); }

const input     = ref("");
const sending   = ref(false);
const lastReply = ref({ loading: false, text: "" });
const isLoading       = computed(() => sending.value || lastReply.value.loading);
const idlePlaceholder = "和 Luna 说点什么吧…";
let dotsTimer = null;
watch(isLoading, () => { clearInterval(dotsTimer); dotsTimer = null; });

function normalizeResponse(res) {
  const data = res?.data ?? res;
  if (typeof data === "string") {
    try { return JSON.parse(data); } catch (e) { return { reply: data }; }
  }
  return data;
}

async function handleModelReply(res) {
  lastReply.value.loading = false;
  if (!res) return;
  const em = res.emotion || "";
  const replyText = res.reply || res.text || "";
  if (!replyText) return;
  if (em) {
    currentEmotion.value = em;
    try { await applyEmotionExpressions(em); } catch (e) { console.warn("[Luna] 表情应用失败", e); }
  }
  await sendReplyAsBubbles(replyText, { interval: 1000, duration: 5000 });
  lastReply.value.text = replyText;
}

function handleNetworkError() {
  lastReply.value.loading = false;
  showAppearanceHint("网络请求失败");
}

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

const historyPanel = ref({
  visible: false, x: 100, y: 100,
  years: [], months: [],
  selectedYear: null, selectedMonth: null, selectedDay: null,
  availableDates: [], availableDatesSet: new Set(),
  loading: false,
});

;(function initHistoryDefaults() {
  const now = new Date();
  const cy  = now.getFullYear();
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
  historyPanel.value.loading     = true;
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
  if (m < 1)       { m = 12; y--; }
  else if (m > 12) { m = 1;  y++; }
  historyPanel.value.selectedYear  = y;
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

function onWheel(ev) {
  const rect        = canvasRef.value.getBoundingClientRect();
  const globalPoint = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  if (!isPointInsideModel(globalPoint)) return;
  ev.preventDefault();
  const factor     = ev.deltaY > 0 ? 0.95 : 1.05;
  const newScale   = Math.min(10, Math.max(0.05, (container.scale.x || 1) * factor));
  const localPoint = container.toLocal(globalPoint, app.stage);
  從斷點處繼續輸出剩餘部分：

src\views\index\index.vue
