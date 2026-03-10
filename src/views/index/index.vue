<template>
  <div class="app-root">

    <!-- ===== 登录幕布（在所有动画之前） ===== -->
    <transition name="login-fade">
      <div v-if="loginVisible" class="login-mask">
        <div
          class="login-terminal"
          :class="{
            collapsed: loginCollapsed,
            'login-error-state': !!loginError,
            'login-success-state': loginSuccess && !loginError
          }"
        >
          <div class="login-header">
            <div class="login-header-left">
              <span class="login-title">LUNA / AUTH</span>
              <span class="login-status" :class="{ ok: !loginError }">
                {{ loginError ? "鉴权失败" : "等待登录" }}
              </span>
            </div>
            <div class="login-header-actions">
              <button class="login-header-btn close" @click="exitApp" title="退出应用">×</button>
            </div>
          </div>
          <div class="login-body">
            <div class="login-log">
              <p v-for="(line, idx) in loginLogLines" :key="idx" class="login-log-line">
                <span class="log-tag">&gt;</span>
                <span class="log-text">{{ line }}</span>
              </p>
            </div>
            <form class="login-form" @submit.prevent="onLoginSubmit">
              <div class="form-row">
                <label>USER</label>
                <input
                  v-model="loginForm.username"
                  type="text"
                  autocomplete="username"
                  spellcheck="false"
                  :disabled="loginLoading"
                />
              </div>
              <div class="form-row">
                <label>PASS</label>
                <input
                  v-model="loginForm.password"
                  type="password"
                  autocomplete="current-password"
                  :disabled="loginLoading"
                />
              </div>
              <button class="login-btn" type="submit" :disabled="loginLoading">
                <span v-if="!loginLoading">登录并启动 LUNA</span>
                <span v-else>正在验证凭证…</span>
              </button>
              <p v-if="loginError" class="login-error">{{ loginError }}</p>
            </form>
          </div>
          <div class="login-footer">
            <span class="login-meta">SESSION ID: {{ loginSessionId }}</span>
            <span class="login-meta">SECURE CHANNEL / AES-256</span>
          </div>
        </div>
      </div>
    </transition>

    <!-- ===== 以下为原有 Luna UI，登录后在其下方启动 ===== -->

    <!-- ===== 背景装饰粒子（仅启动阶段使用） ===== -->
    <div v-if="bgParticlesVisible" class="bg-particles">
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
        <!-- 情绪呼吸灯（不再显示文字 tooltip） -->
        <div class="breath-light" :class="`bl-${currentEmotion}`">
          <div class="bl-core"></div>
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
    <div class="luna-boot-screen">
      <!-- 顶部扫描线 -->
      <div class="scan-line"></div>

      <!-- 主标题 -->
      <div class="boot-title">
        <span class="boot-bracket">[</span>
        <span class="boot-name">LUNA</span>
        <span class="boot-bracket">]</span>
        <span class="boot-version">v2.0.1</span>
      </div>

      <!-- 副标题 -->
      <div class="boot-subtitle">AI 助手核心模块 · 启动中</div>

      <!-- 进度条 -->
      <div class="boot-bar-wrap">
        <div class="boot-bar-track">
          <div class="boot-bar-fill"></div>
        </div>
        <span class="boot-bar-pct">正在加载系统…</span>
      </div>

      <!-- 滚动日志 -->
      <div class="boot-log">
        <div class="log-line" v-for="(line, i) in bootLines" :key="i"
          :style="{ animationDelay: i * 0.18 + 's' }">
          <span class="log-tag">&gt;</span> {{ line }}
        </div>
      </div>

      <!-- 底部装饰 -->
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
  login as loginApi,
  logout as logoutApi,
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
const bgParticlesVisible = ref(true);
const showDebugUI      = ref(false);
const showMessageBox   = ref(false);
const trackingEnabled  = ref(true);
const isSettingOrigin  = ref(false);
const lunaIntroVisible = ref(false);
const codeParticleVisible = ref(false);
const particleCanvasRef   = ref(null);

/* ================= 登录状态 ================= */
const loginVisible   = ref(true);
const loginLoading   = ref(false);
const loginError     = ref("");
const loginSuccess   = ref(false);
const loginForm      = ref({ username: "", password: "" });
const loginSessionId = ref(Math.random().toString(16).slice(2, 10).toUpperCase());
const loginLogLines  = ref([
  "正在建立与鉴权服务的安全连接…",
  "检测到当前会话未认证。",
  "请输入用户名与密码以继续。",
]);
const authToken      = ref("");
const loginCollapsed = ref(false);

function exitApp() {
  window.desktopApi?.quit?.();
}

function runCodeParticleIntro(onDone) {
  codeParticleVisible.value = true;

  nextTick(() => {
    const canvas = particleCanvasRef.value;
    if (!canvas) { onDone?.(); return; }

    const W = canvas.width  = window.innerWidth;
    const H = canvas.height = window.innerHeight;
    const ctx = canvas.getContext("2d");

    // 代码感更强的暗色背景，只在粒子动画期间铺满
    const baseGrad = ctx.createLinearGradient(0, 0, 0, H);
    baseGrad.addColorStop(0,   "#020611");
    baseGrad.addColorStop(0.5, "#050b18");
    baseGrad.addColorStop(1,   "#020611");
    ctx.fillStyle = baseGrad;
    ctx.fillRect(0, 0, W, H);

    const CX    = W / 2;
    // 下移：从 H/2 - 20 改为 H/2 + 60，确保图案不被遮挡
    const CY    = H / 2 + 60;
    const SCALE = Math.min(W, H) / 560;

    // ============================================================
    // 1. 猫脸 + JK少女元素轮廓采样
    // ============================================================
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

      // ── 猫脸主轮廓 ──
      circle(CX, CY, 130 * s, 115 * s, 0.05);
      circle(CX, CY, 115 * s, 100 * s, 0.07);
      circle(CX, CY,  95 * s,  82 * s, 0.10);
      fill(CX - 100 * s, CY - 80 * s, CX + 100 * s, CY + 80 * s, 14, 12);

      // ── 猫耳（左）──
      line(CX - 130 * s, CY - 80  * s, CX - 80 * s, CY - 185 * s, 14);
      line(CX - 80  * s, CY - 185 * s, CX - 38 * s, CY -  95 * s, 14);
      line(CX - 118 * s, CY -  90 * s, CX - 82 * s, CY - 165 * s, 10);
      line(CX - 82  * s, CY - 165 * s, CX - 50 * s, CY - 102 * s, 10);
      fill(CX - 112 * s, CY - 158 * s, CX - 52 * s, CY - 102 * s, 5, 5);

      // ── 猫耳（右）──
      line(CX + 130 * s, CY -  80 * s, CX + 80 * s, CY - 185 * s, 14);
      line(CX + 80  * s, CY - 185 * s, CX + 38 * s, CY -  95 * s, 14);
      line(CX + 118 * s, CY -  90 * s, CX + 82 * s, CY - 165 * s, 10);
      line(CX + 82  * s, CY - 165 * s, CX + 50 * s, CY - 102 * s, 10);
      fill(CX + 52  * s, CY - 158 * s, CX + 112 * s, CY - 102 * s, 5, 5);

      // ── 猫眼（左）──
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

      // ── 猫眼（右）──
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

      // ── 猫鼻 ──
      line(CX - 12 * s, CY + 25 * s, CX + 12 * s, CY + 25 * s, 5);
      line(CX - 12 * s, CY + 25 * s, CX,           CY + 40 * s, 5);
      line(CX + 12 * s, CY + 25 * s, CX,           CY + 40 * s, 5);

      // ── 猫口 ──
      arc(CX - 24 * s, CY + 55 * s, 20 * s, 11 * s, Math.PI * 1.5, Math.PI * 2.0, 0.10);
      arc(CX + 24 * s, CY + 55 * s, 20 * s, 11 * s, Math.PI,       Math.PI * 1.5, 0.10);

      // ── 猫须（左右各4根）──
      line(CX - 38 * s, CY + 28 * s, CX - 125 * s, CY + 18 * s, 10);
      line(CX - 38 * s, CY + 35 * s, CX - 128 * s, CY + 34 * s, 10);
      line(CX - 38 * s, CY + 42 * s, CX - 125 * s, CY + 50 * s, 10);
      line(CX - 38 * s, CY + 22 * s, CX - 118 * s, CY +  5 * s, 10);
      line(CX + 38 * s, CY + 28 * s, CX + 125 * s, CY + 18 * s, 10);
      line(CX + 38 * s, CY + 35 * s, CX + 128 * s, CY + 34 * s, 10);
      line(CX + 38 * s, CY + 42 * s, CX + 125 * s, CY + 50 * s, 10);
      line(CX + 38 * s, CY + 22 * s, CX + 118 * s, CY +  5 * s, 10);

      // ── 腮红 ──
      scatter(CX - 85 * s, CY + 22 * s, 26 * s, 14 * s, 24);
      scatter(CX + 85 * s, CY + 22 * s, 26 * s, 14 * s, 24);

      // ── 蝴蝶结（头顶）──
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

      // ── 衬衣领口 ──
      line(CX, CY + 118 * s, CX - 62 * s, CY + 152 * s, 12);
      line(CX, CY + 118 * s, CX + 62 * s, CY + 152 * s, 12);
      line(CX - 16 * s, CY + 118 * s, CX, CY + 130 * s, 6);
      line(CX + 16 * s, CY + 118 * s, CX, CY + 130 * s, 6);
      circle(CX, CY + 130 * s, 6 * s, 6 * s, 0.30);

      // ── 制服肩部 ──
      arc(CX - 140 * s, CY + 105 * s, 34 * s, 22 * s, Math.PI * 1.4, Math.PI * 2.0, 0.10);
      arc(CX + 140 * s, CY + 105 * s, 34 * s, 22 * s, Math.PI,       Math.PI * 1.6, 0.10);
      line(CX - 140 * s, CY + 105 * s, CX - 90 * s, CY + 82 * s, 10);
      line(CX + 140 * s, CY + 105 * s, CX + 90 * s, CY + 82 * s, 10);

      // ── 樱花瓣（8片）──
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

      // ── 音符（4个）──
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

      // ── 星星（6颗）──
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

      // ── 爱心（2个）──
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

    // ============================================================
    // 2. 字符集
    // ============================================================
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

    // ============================================================
    // 3. 粒子构造
    // ============================================================
    const TOTAL = Math.min(silhouette.length, 700);
    const chosen = silhouette.sort(() => Math.random() - 0.5).slice(0, TOTAL);

    const COLORS = [
      [255, 180, 210],
      [255, 140, 200],
      [200, 160, 255],
      [0,   220, 255],
      [255, 255, 255],
      [255, 200, 230],
      [255, 120, 180],
      [180, 100, 255],
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
        x: sx, y: sy,
        tx: target.x, ty: target.y,
        char: randChar(),
        size: 8 + Math.random() * 7,
        speed: 0.050 + Math.random() * 0.050,
        alpha: 0,
        col,
        phase: Math.random() * Math.PI * 2,
        charTimer: 0,
        charInterval: 3 + Math.floor(Math.random() * 6),
        trail: [],
        trailMax: 5 + Math.floor(Math.random() * 6),
      };
    });

    // ============================================================
    // 4. 代码雨背景
    // ============================================================
    const RAIN_COLS = 34;
    const rainDrops = Array.from({ length: RAIN_COLS }, (_, i) => ({
      x:     (i / RAIN_COLS) * W + Math.random() * (W / RAIN_COLS),
      y:     Math.random() * H,
      speed: 1.6 + Math.random() * 2.8,
      chars: Array.from({ length: 20 }, () => randChar()),
      alpha: 0.07 + Math.random() * 0.10,
      gap:   16 + Math.random() * 8,
    }));

    // ============================================================
    // 5. 帧参数（关键：四个阶段严格分离）
    // ============================================================
    let frame = 0;
    const GATHER   = 60;
    const HOLD     = 45;
    const FADEOUT  = 55;
    const TOTAL_F  = GATHER + HOLD + FADEOUT;
    let rafId;

    // ============================================================
    // 6. 渲染循环（重构：严格阶段判断，保证 DISSOLVE 一定执行）
    // ============================================================
    function tick() {
  ctx.clearRect(0, 0, W, H);

  // 残影拖尾（黑色半透明覆盖）
  ctx.fillStyle = "rgba(0,0,0,0.28)";
  ctx.fillRect(0, 0, W, H);

  const prog_gather = Math.min(1, frame / GATHER);

  // ── 代码雨背景 ──
  const rainAlpha = frame < GATHER
    ? prog_gather * 0.65
    : frame < GATHER + HOLD
      ? 0.65
      : Math.max(0, 0.65 - ((frame - GATHER - HOLD) / FADEOUT) * 1.5);

  rainDrops.forEach((drop) => {
    drop.y += drop.speed;
    if (drop.y > H + drop.chars.length * drop.gap)
      drop.y = -drop.chars.length * drop.gap;
    if (Math.random() < 0.04)
      drop.chars[Math.floor(Math.random() * drop.chars.length)] = randChar();

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

  // ── 主粒子 ──
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
      if (p.charTimer >= p.charInterval) {
        p.char      = randChar();
        p.charTimer = 0;
      }
      } else {
      // FADEOUT：数字化解体——粒子原地颤抖、缩小、溶解为光点
      const t    = (frame - GATHER - HOLD) / FADEOUT;
      const ease = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; // easeInOutQuad
      // 前60%：粒子在原位微颤，后40%：快速淡出
      const jitter = (1 - t) * 2.5 * SCALE;
      p.x = p.tx + (Math.random() - 0.5) * jitter;
      p.y = p.ty + (Math.random() - 0.5) * jitter;
      // alpha：前20帧保持，之后平滑衰减
      p.alpha = t < 0.25 ? 1 : Math.max(0, 1 - (t - 0.25) / 0.75);
      p.alpha = Math.pow(p.alpha, 1.8); // 加速尾部衰减
      // 字符随机闪烁加速（数字化崩溃感）
      if (Math.random() < t * 0.4) p.char = randChar();
    }

    if (p.alpha <= 0.01) return;

    // 拖尾
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

    // 主字符
    ctx.save();
    ctx.globalAlpha = p.alpha;
    ctx.font        = `bold ${p.size}px "Courier New", monospace`;
    ctx.fillStyle   = `rgba(${p.col[0]},${p.col[1]},${p.col[2]},1)`;
    ctx.shadowColor = `rgba(${p.col[0]},${p.col[1]},${p.col[2]},0.9)`;
    ctx.shadowBlur  = 14;
    ctx.fillText(p.char, p.x, p.y);
    ctx.restore();
  });

  // ── 汇聚中心光晕 ──
  if (frame >= GATHER * 0.5 && frame < GATHER + HOLD) {
    const prog    = Math.min(1, (frame - GATHER * 0.5) / (GATHER * 0.5));
    const fadeOut = frame >= GATHER + HOLD * 0.6
      ? 1 - (frame - GATHER - HOLD * 0.6) / (HOLD * 0.4)
      : 1;
    const radius = 280 * SCALE * prog;
    const grd    = ctx.createRadialGradient(CX, CY, 0, CX, CY, radius);
    grd.addColorStop(0,   `rgba(255,160,220,${0.09 * prog * fadeOut})`);
    grd.addColorStop(0.4, `rgba(180,120,255,${0.05 * prog * fadeOut})`);
    grd.addColorStop(1,   "rgba(0,0,0,0)");
    ctx.fillStyle = grd;
    ctx.fillRect(0, 0, W, H);
  }

  // ── 保持阶段扫描线 ──
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

    // ── FADEOUT 阶段：数字化溶解光效（无黑色蒙版，纯透明消散） ──
  if (frame >= GATHER + HOLD) {
    const t = (frame - GATHER - HOLD) / FADEOUT;

    // 阶段一（t < 0.35）：中心向外扩散一道能量脉冲环
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

    // 阶段二（t > 0.15）：中心渐渐亮起一个白色核心光点，再淡出
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

    // 整体透明度衰减：用 globalAlpha 对整个 canvas 做最终淡出
    // 在最后 30% 时间内，整体 canvas 渐变透明（不用黑色蒙版）
    if (t > 0.70) {
      const fadeT = (t - 0.70) / 0.30;
      const fadeA = fadeT * fadeT * 0.96;
      ctx.fillStyle = `rgba(2,6,17,${fadeA})`; // 与背景色一致的深蓝黑，非纯黑
      ctx.fillRect(0, 0, W, H);
    }
  }

  // ── HUD 装饰文字（仅在前两阶段显示，FADEOUT 时淡出）──
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
  "正在初始化神经接口…",
  "正在加载 Live2D 核心模块…",
  "正在挂载表情合成引擎…",
  "正在校准视线追踪参数…",
  "正在连接 Luna 对话服务…",
  "正在预热语言模型核心…",
  "系统已就绪，等待指令。",
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
  // 愤怒类
  Angry:        "愤怒",
  Annoyed:      "烦躁",
  Irritated:    "不耐烦",
  Frustrated:   "沮丧",
  Determined:   "坚定",
  // 悲伤类
  Sad:          "难过",
  Lonely:       "孤独",
  Despair:      "绝望",
  Broken:       "心碎",
  Uneasy:       "不安",
  Resigned:     "无奈",
  Disappointed: "失望",
  // 恐惧/焦虑类
  Anxious:      "焦虑",
  Fearful:      "恐惧",
  Shocked:      "震惊",
  // 疲惫/无聊类
  Tired:        "疲惫",
  Bored:        "无聊",
  // 困惑类
  Confused:     "困惑",
  // 尴尬/慌乱类
  Embarrassed:  "尴尬",
  Flustered:    "慌乱",
  // 温柔/爱意类
  Affectionate: "温柔",
  Clingy:       "黏人",
  Shy:          "害羞",
  Soft:         "柔软",
  Tsundere:     "傲娇",
  Grateful:     "感激",
  Relieved:     "释然",
  // 戏谑类
  Teasing:      "戏弄",
  Yandere:      "病娇",
  // 开心/希望类
  Smile:        "微笑",
  Hopeful:      "期待",
  Proud:        "骄傲",
  // 庄重/平静类：冷白灰，极慢 ========== */
  // neutral（兼容小写）
  neutral:      "平静",
  // 兼容小写
  happy:        "开心",
  sad:          "难过",
  angry:        "生气",
  surprised:    "惊讶",
  shy:          "害羞",
};
const emotionLabel = computed(() => EMOTION_LABEL_MAP[currentEmotion.value] || currentEmotion.value);

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

/* ================= 登录逻辑 ================= */
async function performLogin() {
  if (!loginForm.value.username || !loginForm.value.password) {
    loginError.value = "用户名或密码不能为空";
    return;
  }
  loginLoading.value = true;
  loginError.value = "";
  loginSuccess.value = false;
  loginLogLines.value.push("正在向鉴权服务发送凭证…");
  try {
    const data = await loginApi({
      username: loginForm.value.username,
      password: loginForm.value.password,
    });
    const token = data?.token || "";
    if (!token) {
      loginError.value = "鉴权服务未返回有效 Token";
      loginLogLines.value.push("鉴权失败：Token 缺失。");
      return;
    }
    authToken.value = token;
    loginSuccess.value = true;
    loginLogLines.value.push("鉴权通过，正在启动 LUNA 核心…");
    // 先淡出登录幕布，再启动加载/粒子动画
    loginVisible.value = false;
    setTimeout(() => {
      startBootSequence();
    }, 450);
  } catch (e) {
    console.error("[Auth] 登录请求失败", e);
    loginError.value = "无法连接鉴权服务，请检查网络或服务状态";
    loginLogLines.value.push("网络错误：无法连接到鉴权端点。");
  } finally {
    loginLoading.value = false;
  }
}

function onLoginSubmit() {
  if (!loginLoading.value) performLogin();
}

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

let app       = null;
let container = null;
let model     = null;
const expressionCache = new Map();

/* ================= 气泡 ================= */
const { chatBubbles, bubbleAnchor, registerBubble, sendReplyAsBubbles } = useBubble(messageBoxRef, showMessageBox);

/* ================= 外貌 ================= */
const {
  APPEARANCE_FILES, appearanceEnabled, appearanceAppliedMeta,
  appearanceHint, showAppearanceHint, displayAppearanceName,
  loadAppearanceState, saveAppearanceState, applyAppearanceFile,
  removeAppearanceFile, applyAllEnabled, disableAll, onAppearanceToggle,
} = useAppearance();

/* ================= 律动 ================= */
const { showSystemAudioListening: rhythmShowListening, toggleSystemAudio, dispose: disposeRhythm } = useRhythm();

function getCoreModel() { return model?.internalModel?.coreModel ?? null; }

async function onAppearanceToggleUI(file) { await onAppearanceToggle(file, getCoreModel()); }
async function applyAllEnabledUI()        { await applyAllEnabled(getCoreModel()); }
async function disableAllUI()             { await disableAll(getCoreModel()); }
async function onToggleSystemAudio()      { await toggleSystemAudio(getCoreModel(), trackingEnabled); }

/* ================= 聊天输入 ================= */
const input     = ref("");
const sending   = ref(false);
const lastReply = ref({ loading: false, text: "" });

const isLoading       = computed(() => sending.value || lastReply.value.loading);
const idlePlaceholder = "和 Luna 说点什么吧…";

let dotsTimer = null;
watch(isLoading, () => { clearInterval(dotsTimer); dotsTimer = null; });

/* ================= 响应处理 ================= */
function normalizeResponse(res) {
  const data = res?.data ?? res;
  if (typeof data === "string") {
    try { return JSON.parse(data); } catch (e) { return { reply: data }; }
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
    try { await applyEmotionExpressions(em); } catch (e) { console.warn("[Luna] 表情应用失败", e); }
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
  if (overModel) window.pet?.enter();
  else window.pet?.leave();
}
function uiEnter() { overUI = true;  updatePetState(); }
function uiLeave() { overUI = false; updatePetState(); }

watch(showMessageBox, () => { updatePetState(); });
watch(showDebugUI,   () => { updatePetState(); });

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
  } catch (e) { console.warn("[Luna] applyLookAt 失败", e); }
}

function onGlobalPointerMove(ev) {
  if (!trackingEnabled.value || !lookOriginLocal || !model) return;
  const rect  = canvasRef.value.getBoundingClientRect();
  const world = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  const local = container.toLocal(world, app.stage);
  applyLookAt(local.x - lookOriginLocal.x, local.y - lookOriginLocal.y);
}

function onCanvasClick(ev) {
  if (!isSettingOrigin.value) return;
  const rect  = canvasRef.value.getBoundingClientRect();
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

/* ================= 调试 UI ================= */
function toggleTracking() {
  trackingEnabled.value = !trackingEnabled.value;
  if (!trackingEnabled.value) {
    const core = getCoreModel();
    if (core) {
      [PARAM_CONFIG.EYE_X.param, PARAM_CONFIG.EYE_Y.param,
       PARAM_CONFIG.HEAD_X.param, PARAM_CONFIG.HEAD_Y.param]
        .forEach((p) => { try { core.setParameterValueById(p, 0); } catch (e) { console.warn("[Luna] 重置参数失败", e); } });
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
    try { core.setParameterValueById(PARAM_CONFIG.BREATH.param, val); } catch (e) { console.warn("[Luna] 呼吸参数失败", e); }
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
      if (Blend === "Add")           targetValues[Id] = base + Value;
      else if (Blend === "Multiply") targetValues[Id] = base * Value;
      else                           targetValues[Id] = Value;
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
async function closeLuna() {
  contextMenu.value.visible = false;
  try {
    // 调用后端登出接口，清除当前 Token 会话
    if (authToken.value) {
      try {
        await logoutApi(authToken.value);
      } catch (e) {
        console.warn("[Auth] 登出失败", e);
      }
    }
    await callShutdown();
  } catch (e) { console.warn("[Luna] shutdown 失败", e); }
  try {
    stopBreath();
    app?.destroy(true);
    window.pet?.leave?.();
  } catch (e) {
    console.warn("[Luna] 关闭出错", e);
  }
  if (wrapperRef.value) wrapperRef.value.innerHTML = "";
  showMessageBox.value = false;
  // 通知 Electron 主进程终止
  window.desktopApi?.quit?.();
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

/* ================= 启动序列：登录通过后再触发 ================= */
async function startBootSequence() {
  // 启动科幻启动遮罩
  lunaIntroVisible.value = true;

  // Boot 动画结束后，先播代码粒子动画，再显示模型
  gsap.delayedCall(3.2, () => {
    lunaIntroVisible.value = false;

    if (!model || !app) {
      return;
    }

    // 模型提前移到正确位置并开始在后台渲染，保持透明
    model.alpha = 0;
    model.y     = app.renderer.height;

    // 粒子动画启动
    runCodeParticleIntro(() => {
      if (!model || !app) return;

      // 粒子结束后模型采用柔和的渐显 + 轻微上移过渡
      gsap.fromTo(
        model,
        {
          alpha: 0,
          y: app.renderer.height + 40,
        },
        {
          alpha: 1,
          y: app.renderer.height,
          duration: 1.2,
          ease: "power3.out",
        }
      );

      // 启动画面结束后，彻底关闭背景粒子，只保留模型
      bgParticlesVisible.value = false;
    });
  });

  // 启动表情与后端会话
  await applyEmotionExpressions(INITIAL_EMOTION);
  await callStartup();
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

  model.alpha = 0;
  model.y     = app.renderer.height + 60;

  model
    .on("pointerdown",      onPointerDown)
    .on("pointermove",      onPointerMove)
    .on("pointerup",        onPointerUp)
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
.app-root {
  width: 100vw;
  height: 100vh;
  position: relative;
  overflow: hidden;
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
  /* 结束动画后重新让桌面透出，只保留模型 */
  background: transparent;
}

/* ===== 登录幕布 ===== */
.login-mask {
  position: fixed;
  inset: 0;
  z-index: 9100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  font-family: "Courier New", "Consolas", monospace;
}
.login-terminal {
  width: 520px;
  max-width: 90vw;
  border-radius: 6px;
  background: radial-gradient(circle at 0 0, rgba(0,255,200,0.12), transparent 55%),
    radial-gradient(circle at 100% 100%, rgba(120,120,255,0.1), transparent 55%),
    linear-gradient(165deg, #04070d, #050a13 45%, #050913);
  box-shadow:
    0 0 0 1px rgba(0,255,200,0.25),
    0 18px 40px rgba(0,0,0,0.9),
    0 0 60px rgba(0,255,200,0.15);
  border: 1px solid rgba(0,255,200,0.3);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.login-terminal.collapsed {
  width: 280px;
  transform: translateY(140px);
}
.login-terminal.collapsed .login-body {
  display: none;
}
.login-terminal.collapsed .login-footer {
  display: none;
}
.login-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid rgba(0,255,200,0.28);
  background: linear-gradient(90deg, rgba(0,0,0,0.9), rgba(0,40,40,0.9));
}
.login-header-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.login-title {
  font-size: 12px;
  letter-spacing: 0.16em;
  color: rgba(0,255,200,0.9);
}
.login-status {
  font-size: 11px;
  color: rgba(255,180,180,0.9);
}
.login-status.ok {
  color: rgba(0,255,200,0.9);
}
.login-header-actions {
  display: flex;
  gap: 4px;
  margin-left: 8px;
}
.login-header-btn {
  width: 18px;
  height: 18px;
  border-radius: 3px;
  border: 1px solid rgba(0,255,200,0.3);
  background: radial-gradient(circle at 30% 0, rgba(0,255,200,0.25), transparent 60%),
    rgba(0,10,10,0.85);
  color: rgba(0,255,200,0.9);
  font-size: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition: background 0.15s ease, color 0.15s ease, transform 0.12s ease, border-color 0.15s ease;
}
.login-header-btn:hover {
  background: radial-gradient(circle at 30% 0, rgba(0,255,200,0.4), transparent 60%),
    rgba(0,25,25,0.95);
  transform: translateY(-1px);
}
.login-header-btn.close {
  border-color: rgba(0,255,200,0.6);
}
.login-header-btn.close:hover {
  box-shadow: 0 0 10px rgba(0,255,200,0.5);
}
.login-body {
  display: flex;
  padding: 10px 14px 8px;
  gap: 12px;
}
.login-log {
  flex: 1.2;
  max-height: 150px;
  padding-right: 6px;
  overflow-y: auto;
  font-size: 11px;
  color: rgba(0,255,200,0.72);
}
.login-log-line {
  display: flex;
  gap: 6px;
  margin: 0 0 4px;
}
.login-log-line .log-tag {
  color: rgba(0,255,200,0.5);
}
.login-log-line .log-text {
  letter-spacing: 0.04em;
}
.login-form {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.form-row label {
  font-size: 11px;
  color: rgba(0,255,200,0.7);
  letter-spacing: 0.16em;
}
.form-row input {
  background: rgba(0,0,0,0.8);
  border-radius: 3px;
  border: 1px solid rgba(0,255,200,0.28);
  padding: 5px 7px;
  color: #e8fff8;
  font-size: 12px;
  outline: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.2s;
}
.form-row input:focus {
  border-color: rgba(0,255,200,0.7);
  box-shadow: 0 0 0 1px rgba(0,255,200,0.36);
  background: rgba(0,10,10,0.9);
}
.login-btn {
  margin-top: 4px;
  padding: 6px 10px;
  border-radius: 3px;
  border: 1px solid rgba(0,255,200,0.6);
  background: linear-gradient(90deg, rgba(0,255,200,0.3), rgba(0,150,255,0.3));
  color: #eaffff;
  font-size: 11px;
  letter-spacing: 0.14em;
  cursor: pointer;
  text-transform: uppercase;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease, transform 0.13s ease, box-shadow 0.2s ease, opacity 0.15s;
}
.login-btn:hover:not(:disabled) {
  background: linear-gradient(90deg, rgba(0,255,200,0.5), rgba(0,170,255,0.5));
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(0,0,0,0.7);
}
.login-btn:active:not(:disabled) {
  transform: translateY(0) scale(0.98);
  box-shadow: none;
}
.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.login-error {
  margin: 2px 0 0;
  font-size: 11px;
  color: #ff7b7b;
}
.login-footer {
  display: flex;
  justify-content: space-between;
  padding: 6px 14px 8px;
  border-top: 1px solid rgba(0,255,200,0.18);
  background: rgba(0,0,0,0.9);
}
.login-meta {
  font-size: 10px;
  color: rgba(0,255,200,0.65);
  letter-spacing: 0.08em;
}

.login-fade-enter-active,
.login-fade-leave-active {
  transition: opacity 0.35s ease;
}
.login-fade-enter-from,
.login-fade-leave-to {
  opacity: 0;
}

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
  0%, 100% { transform: translateY(0);    }
  50%       { transform: translateY(-3px); }
}
@keyframes bubbleIn {
  from { opacity: 0; transform: scale(0.8) translateY(10px); }
  to   { opacity: 1; transform: scale(1)   translateY(0);    }
}
@keyframes bubbleOut {
  from { opacity: 1; transform: scale(1); }
  to   { opacity: 0; transform: scale(0.88) translateY(6px); }
}
.css-chat-bubble.leaving {
  animation: bubbleOut 0.2s ease-in forwards;
}

.messageBox {
  position: absolute;
  top: 800px;
  left: 490px;
  width: 700px;
  height: 56px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 0% 0%, rgba(72, 221, 255, 0.12), transparent 45%),
    radial-gradient(circle at 100% 100%, rgba(255, 116, 207, 0.12), transparent 45%),
    linear-gradient(135deg, #111111 0%, #1e1e1e 45%, #242424 100%);
  padding: 0 14px;
  border-radius: 28px;
  border: 1.5px solid rgba(255,255,255,0.10);
  z-index: 1001;
  pointer-events: auto;
  box-shadow:
    0 0 0 1px rgba(0,0,0,0.75),
    0 16px 40px rgba(0,0,0,0.55),
    0 0 22px rgba(90, 230, 255, 0.18);
  backdrop-filter: blur(14px);
  transition:
    border-color 0.4s ease,
    box-shadow 0.4s ease,
    transform 0.25s cubic-bezier(0.34,1.56,0.64,1),
    background-position 0.45s ease-out;
  gap: 8px;
}
.messageBox::before {
  content: "";
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  border: 1px solid rgba(255,255,255,0.05);
  background:
    linear-gradient(120deg, rgba(88, 220, 255, 0.18), transparent 45%, rgba(255, 140, 220, 0.16) 70%, transparent 100%);
  mix-blend-mode: screen;
  opacity: 0;
  transform: translateX(-20%);
  pointer-events: none;
  transition: opacity 0.35s ease, transform 0.45s ease;
}
.messageBox::after {
  content: "";
  position: absolute;
  inset: 0;
  background-image: linear-gradient(
    120deg,
    rgba(255,255,255,0.12) 0,
    transparent 8%,
    transparent 92%,
    rgba(255,255,255,0.1) 100%
  );
  opacity: 0;
  mix-blend-mode: soft-light;
  animation: msgBoxScan 3.2s linear infinite;
  pointer-events: none;
}
.messageBox:hover {
  transform: translateY(-2px) scale(1.01);
  box-shadow:
    0 0 0 1px rgba(0,0,0,0.75),
    0 18px 50px rgba(0,0,0,0.65),
    0 0 28px rgba(120, 240, 255, 0.24);
}
.messageBox:hover::before {
  opacity: 1;
  transform: translateX(0);
}
.messageBox.emotion-happy::before,
.messageBox.emotion-Smile::before {
  background: linear-gradient(120deg, rgba(255, 220, 120, 0.4), transparent 45%, rgba(255, 180, 90, 0.3) 70%, transparent 100%);
}
.messageBox.emotion-angry::before,
.messageBox.emotion-Angry::before {
  background: linear-gradient(120deg, rgba(255, 120, 120, 0.45), transparent 45%, rgba(255, 70, 110, 0.35) 70%, transparent 100%);
}
.messageBox.emotion-sad::before,
.messageBox.emotion-Sad::before {
  background: linear-gradient(120deg, rgba(90, 150, 255, 0.4), transparent 45%, rgba(40, 110, 255, 0.35) 70%, transparent 100%);
}
.messageBox.emotion-shy::before,
.messageBox.emotion-Shy::before {
  background: linear-gradient(120deg, rgba(255, 150, 210, 0.4), transparent 45%, rgba(255, 120, 190, 0.3) 70%, transparent 100%);
}
.messageBox.emotion-Solemn::before,
.messageBox.emotion-neutral::before {
  background: linear-gradient(120deg, rgba(220, 220, 235, 0.26), transparent 45%, rgba(180, 190, 210, 0.22) 70%, transparent 100%);
}
@keyframes msgBoxScan {
  0% {
    opacity: 0;
    transform: translateX(-20%);
  }
  8% {
    opacity: 0.35;
  }
  40% {
    opacity: 0.15;
    transform: translateX(6%);
  }
  80% {
    opacity: 0;
    transform: translateX(20%);
  }
  100% {
    opacity: 0;
    transform: translateX(20%);
  }
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

/* ================= 呼吸灯 ================= */
.breath-light {
  position: relative;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: default;
}

/* 核心光点 */
.bl-core {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255,255,255,0.6);
  box-shadow: 0 0 6px 2px rgba(255,255,255,0.25);
  animation: blBreathe var(--bl-duration, 3s) ease-in-out infinite;
  transition: background 0.5s ease, box-shadow 0.5s ease;
}

/* 外圈光晕（伪元素） */
.bl-core::after {
  content: "";
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--bl-color, rgba(255,255,255,0.15)) 0%, transparent 70%);
  animation: blGlow var(--bl-duration, 3s) ease-in-out infinite;
  opacity: 0;
}

/* 原 tooltip 样式已废弃（不再显示文字） */

/* 呼吸动画 */
@keyframes blBreathe {
  0%, 100% { transform: scale(1);    opacity: 0.55; }
  50%       { transform: scale(var(--bl-scale, 1.6)); opacity: 1; }
}
@keyframes blGlow {
  0%, 100% { opacity: 0;   transform: scale(1); }
  50%       { opacity: 0.6; transform: scale(var(--bl-scale, 1.6)); }
}

/* ===== 各情绪变量 ===== */

/* ================= 呼吸灯完整样式 ================= */
.breath-light {
  position: relative;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: default;
}

.bl-core {
  position: relative;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255,255,255,0.6);
  box-shadow: 0 0 6px 2px rgba(255,255,255,0.25);
  animation: blBreathe 3s ease-in-out infinite;
  transition: background 0.5s ease, box-shadow 0.5s ease, animation-duration 0.5s ease;
}

.bl-core::after {
  content: "";
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  animation: blGlow 3s ease-in-out infinite;
  opacity: 0;
}

.bl-tooltip {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%) translateY(4px);
  background: rgba(20,20,20,0.92);
  color: rgba(255,255,255,0.75);
  font-size: 10px;
  padding: 3px 8px;
  border-radius: 8px;
  border: 1px solid rgba(255,255,255,0.08);
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease, transform 0.2s ease;
  letter-spacing: 0.04em;
  z-index: 10;
}
.breath-light:hover .bl-tooltip {
  opacity: 1;
  transform: translateX(-50%) translateY(-2px);
}

@keyframes blBreathe {
  0%, 100% { transform: scale(1);    opacity: 0.55; }
  50%       { transform: scale(1.7); opacity: 1;    }
}
@keyframes blGlow {
  0%, 100% { opacity: 0;   transform: scale(1);   }
  50%       { opacity: 0.5; transform: scale(1.7); }
}

/* ========== 愤怒类：红色系，快速，大幅 ========== */
/* Angry */
.bl-Angry .bl-core {
  background: rgba(255,60,60,0.90);
  box-shadow: 0 0 10px 4px rgba(255,60,60,0.40);
  animation-duration: 1.1s;
}
/* Annoyed */
.bl-Annoyed .bl-core {
  background: rgba(255,100,60,0.88);
  box-shadow: 0 0 9px 3px rgba(255,100,60,0.35);
  animation-duration: 1.3s;
}
/* Irritated */
.bl-Irritated .bl-core {
  background: rgba(255,130,60,0.85);
  box-shadow: 0 0 8px 3px rgba(255,130,60,0.30);
  animation-duration: 1.5s;
}
/* Frustrated */
.bl-Frustrated .bl-core {
  background: rgba(220,80,80,0.88);
  box-shadow: 0 0 9px 3px rgba(220,80,80,0.32);
  animation-duration: 1.2s;
}
/* Determined */
.bl-Determined .bl-core {
  background: rgba(200,80,60,0.85);
  box-shadow: 0 0 8px 3px rgba(200,80,60,0.30);
  animation-duration: 1.4s;
}

/* ========== 悲伤类：蓝色系，慢速，小幅 ========== */
/* Sad */
.bl-Sad .bl-core {
  background: rgba(90,150,255,0.80);
  box-shadow: 0 0 7px 2px rgba(90,150,255,0.25);
  animation-duration: 4.5s;
}
/* Lonely */
.bl-Lonely .bl-core {
  background: rgba(110,140,220,0.75);
  box-shadow: 0 0 6px 2px rgba(110,140,220,0.22);
  animation-duration: 5.0s;
}
/* Despair */
.bl-Despair .bl-core {
  background: rgba(70,90,180,0.80);
  box-shadow: 0 0 7px 2px rgba(70,90,180,0.25);
  animation-duration: 5.5s;
}
/* Broken */
.bl-Broken .bl-core {
  background: rgba(60,70,140,0.75);
  box-shadow: 0 0 6px 2px rgba(60,70,140,0.20);
  animation-duration: 6.0s;
}
/* Uneasy */
.bl-Uneasy .bl-core {
  background: rgba(130,160,230,0.78);
  box-shadow: 0 0 6px 2px rgba(130,160,230,0.22);
  animation-duration: 4.0s;
}
/* Resigned */
.bl-Resigned .bl-core {
  background: rgba(120,130,180,0.72);
  box-shadow: 0 0 5px 2px rgba(120,130,180,0.18);
  animation-duration: 5.5s;
}
/* Disappointed */
.bl-Disappointed .bl-core {
  background: rgba(100,120,200,0.75);
  box-shadow: 0 0 6px 2px rgba(100,120,200,0.20);
  animation-duration: 4.8s;
}

/* ========== 恐惧/焦虑类：青紫色系，快速不规则 ========== */
/* Anxious */
.bl-Anxious .bl-core {
  background: rgba(160,100,255,0.82);
  box-shadow: 0 0 8px 3px rgba(160,100,255,0.28);
  animation-duration: 1.6s;
}
/* Fearful */
.bl-Fearful .bl-core {
  background: rgba(130,80,220,0.80);
  box-shadow: 0 0 8px 3px rgba(130,80,220,0.26);
  animation-duration: 1.4s;
}
/* Shocked */
.bl-Shocked .bl-core {
  background: rgba(180,120,255,0.88);
  box-shadow: 0 0 10px 4px rgba(180,120,255,0.35);
  animation-duration: 1.0s;
}

/* ========== 疲惫/无聊类：灰色系，极慢，微幅 ========== */
/* Tired */
.bl-Tired .bl-core {
  background: rgba(160,160,170,0.65);
  box-shadow: 0 0 5px 1px rgba(160,160,170,0.18);
  animation-duration: 6.0s;
}
/* Bored */
.bl-Bored .bl-core {
  background: rgba(150,150,155,0.60);
  box-shadow: 0 0 4px 1px rgba(150,150,155,0.15);
  animation-duration: 7.0s;
}

/* ========== 困惑类：橙黄色系，中速 ========== */
/* Confused */
.bl-Confused .bl-core {
  background: rgba(255,190,60,0.82);
  box-shadow: 0 0 8px 3px rgba(255,190,60,0.28);
  animation-duration: 2.0s;
}

/* ========== 尴尬/慌乱类：橙粉色系 ========== */
/* Embarrassed */
.bl-Embarrassed .bl-core {
  background: rgba(255,140,120,0.82);
  box-shadow: 0 0 8px 3px rgba(255,140,120,0.28);
  animation-duration: 2.0s;
}
/* Flustered */
.bl-Flustered .bl-core {
  background: rgba(255,120,100,0.85);
  box-shadow: 0 0 9px 3px rgba(255,120,100,0.30);
  animation-duration: 1.6s;
}

/* ========== 温柔/爱意类：粉色系，中慢速 ========== */
/* Affectionate */
.bl-Affectionate .bl-core {
  background: rgba(255,160,200,0.82);
  box-shadow: 0 0 9px 3px rgba(255,160,200,0.30);
  animation-duration: 2.5s;
}
/* Clingy */
.bl-Clingy .bl-core {
  background: rgba(255,140,180,0.80);
  box-shadow: 0 0 8px 3px rgba(255,140,180,0.28);
  animation-duration: 2.2s;
}
/* Shy */
.bl-Shy .bl-core {
  background: rgba(255,150,200,0.80);
  box-shadow: 0 0 8px 3px rgba(255,150,200,0.28);
  animation-duration: 2.8s;
}
/* Soft */
.bl-Soft .bl-core {
  background: rgba(255,180,210,0.78);
  box-shadow: 0 0 7px 2px rgba(255,180,210,0.24);
  animation-duration: 3.2s;
}
/* Tsundere */
.bl-Tsundere .bl-core {
  background: rgba(255,120,160,0.82);
  box-shadow: 0 0 8px 3px rgba(255,120,160,0.28);
  animation-duration: 2.0s;
}
/* Grateful */
.bl-Grateful .bl-core {
  background: rgba(255,170,180,0.80);
  box-shadow: 0 0 8px 3px rgba(255,170,180,0.26);
  animation-duration: 2.8s;
}
/* Relieved/* Relieved */
.bl-Relieved .bl-core {
  background: rgba(200,230,255,0.80);
  box-shadow: 0 0 7px 2px rgba(200,230,255,0.24);
  animation-duration: 3.5s;
}

/* ========== 戏谑/特殊类 ========== */
/* Teasing */
.bl-Teasing .bl-core {
  background: rgba(255,200,80,0.85);
  box-shadow: 0 0 9px 3px rgba(255,200,80,0.30);
  animation-duration: 1.8s;
}
/* Yandere：深红紫，快且大 */
.bl-Yandere .bl-core {
  background: rgba(200,50,120,0.90);
  box-shadow: 0 0 12px 5px rgba(200,50,120,0.40);
  animation-duration: 1.0s;
}

/* ========== 开心/希望类：黄绿色系，中速 ========== */
/* Smile */
.bl-Smile .bl-core {
  background: rgba(255,220,80,0.85);
  box-shadow: 0 0 8px 3px rgba(255,220,80,0.30);
  animation-duration: 2.2s;
}
/* Hopeful */
.bl-Hopeful .bl-core {
  background: rgba(180,230,120,0.82);
  box-shadow: 0 0 8px 3px rgba(180,230,120,0.28);
  animation-duration: 2.5s;
}
/* Proud */
.bl-Proud .bl-core {
  background: rgba(255,210,60,0.88);
  box-shadow: 0 0 10px 4px rgba(255,210,60,0.32);
  animation-duration: 2.0s;
}

/* ========== 庄重/平静类：冷白灰，极慢 ========== */
/* Solemn */
.bl-Solemn .bl-core {
  background: rgba(200,200,210,0.60);
  box-shadow: 0 0 5px 2px rgba(200,200,210,0.15);
  animation-duration: 5.0s;
}
/* neutral（兼容小写） */
.bl-neutral .bl-core {
  background: rgba(220,220,220,0.65);
  box-shadow: 0 0 6px 2px rgba(220,220,220,0.18);
  animation-duration: 3.5s;
}

/* ========== 通用回退 ========== */
.bl-happy .bl-core {
  background: rgba(255,220,80,0.85);
  box-shadow: 0 0 8px 3px rgba(255,220,80,0.30);
  animation-duration: 2.2s;
}
.bl-sad .bl-core {
  background: rgba(100,160,255,0.80);
  box-shadow: 0 0 7px 2px rgba(100,160,255,0.25);
  animation-duration: 4.2s;
}
.bl-angry .bl-core {
  background: rgba(255,80,80,0.90);
  box-shadow: 0 0 10px 4px rgba(255,80,80,0.35);
  animation-duration: 1.2s;
}
.bl-surprised .bl-core {
  background: rgba(180,120,255,0.85);
  box-shadow: 0 0 9px 3px rgba(180,120,255,0.30);
  animation-duration: 1.5s;
}
.bl-shy .bl-core {
  background: rgba(255,150,200,0.80);
  box-shadow: 0 0 8px 3px rgba(255,150,200,0.28);
  animation-duration: 2.8s;
}

.emotion-glyph {
  position: relative;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  cursor: default;
  display: flex;
  align-items: center;
  justify-content: center;
}
.emotion-glyph svg {
  width: 28px;
  height: 28px;
  overflow: visible;
  animation: glyphPulse 3s ease-in-out infinite;
}
.eg-ring {
  fill: none;
  stroke: rgba(255,255,255,0.15);
  stroke-width: 1.2;
  transition: stroke 0.4s ease;
}
.eg-inner {
  fill: none;
  stroke: rgba(255,255,255,0.55);
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: stroke 0.4s ease;
}
.eg-happy     .eg-ring  { stroke: rgba(255,220,80,0.35); }
.eg-happy     .eg-inner { stroke: rgba(255,220,80,0.90); }
.eg-sad       .eg-ring  { stroke: rgba(100,160,255,0.35); }
.eg-sad       .eg-inner { stroke: rgba(100,160,255,0.90); }
.eg-angry     .eg-ring  { stroke: rgba(255,90,90,0.35); }
.eg-angry     .eg-inner { stroke: rgba(255,90,90,0.90); }
.eg-shy       .eg-ring  { stroke: rgba(255,150,200,0.35); }
.eg-shy       .eg-inner { stroke: rgba(255,150,200,0.90); }
.eg-surprised .eg-ring  { stroke: rgba(180,120,255,0.35); }
.eg-surprised .eg-inner { stroke: rgba(180,120,255,0.90); }
.eg-Solemn    .eg-ring  { stroke: rgba(255,255,255,0.18); }
.eg-Solemn    .eg-inner { stroke: rgba(255,255,255,0.50); }
.eg-neutral   .eg-ring  { stroke: rgba(255,255,255,0.12); }
.eg-neutral   .eg-inner { stroke: rgba(255,255,255,0.40); }
.emotion-glyph:hover .eg-inner   { filter: drop-shadow(0 0 3px currentColor); }
.emotion-glyph:hover .eg-tooltip { opacity: 1; transform: translateX(-50%) translateY(-2px); }
.eg-tooltip {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%) translateY(4px);
  background: rgba(20,20,20,0.92);
  color: rgba(255,255,255,0.75);
  font-size: 10px;
  padding: 3px 8px;
  border-radius: 8px;
  border: 1px solid rgba(255,255,255,0.08);
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease, transform 0.2s ease;
  letter-spacing: 0.04em;
}
@keyframes glyphPulse {
  0%, 100% { opacity: 0.75; transform: scale(1);    }
  50%       { opacity: 1;    transform: scale(1.08); }
}

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

.luna-loader {
  position: relative;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.luna-loader-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  animation: loaderRotate 1.8s linear infinite;
}
.loader-track {
  fill: none;
  stroke: rgba(255,255,255,0.08);
  stroke-width: 2.5;
}
.loader-arc {
  fill: none;
  stroke: rgba(255,255,255,0.75);
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-dasharray: 28 60;
  animation: loaderArcPulse 1.8s ease-in-out infinite;
}
.loader-core {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255,255,255,0.85);
  animation: loaderCorePulse 1.8s ease-in-out infinite;
  box-shadow: 0 0 6px rgba(255,255,255,0.5);
}
@keyframes loaderRotate {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}
@keyframes loaderArcPulse {
  0%, 100% { stroke-dasharray: 20 68; opacity: 0.6; }
  50%       { stroke-dasharray: 40 48; opacity: 1;   }
}
@keyframes loaderCorePulse {
  0%, 100% { transform: scale(0.7); opacity: 0.5; }
  50%       { transform: scale(1.3); opacity: 1;   }
}

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

.msgbox-fade-enter-active { transition: opacity 0.35s ease, transform 0.35s cubic-bezier(0.34,1.56,0.64,1); }
.msgbox-fade-leave-active { transition: opacity 0.2s ease,  transform 0.2s ease; }
.msgbox-fade-enter-from   { opacity: 0; transform: translateY(16px) scale(0.95); }
.msgbox-fade-leave-to     { opacity: 0; transform: translateY(8px)  scale(0.97); }

.debug-ui {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 1002;
  position: absolute;
  display: flex;
  gap: 8px;
  background:
    radial-gradient(circle at 0 0, rgba(72, 221, 255, 0.18), transparent 52%),
    radial-gradient(circle at 100% 100%, rgba(255, 116, 207, 0.15), transparent 55%),
    linear-gradient(145deg, rgba(8,8,8,0.98), rgba(18,18,18,0.96));
  padding: 10px 12px;
  border-radius: 16px;
  box-shadow: 0 4px 18px rgba(0,0,0,0.3);
  border: 1px solid rgba(255,255,255,0.07);
  pointer-events: auto;
  backdrop-filter: blur(8px);
  overflow: hidden;
}
.debug-ui::before {
  content: "";
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  border: 1px solid rgba(255,255,255,0.03);
  background-image:
    linear-gradient(90deg, rgba(255,255,255,0.04) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255,255,255,0.035) 1px, transparent 1px);
  background-size: 18px 18px;
  mix-blend-mode: soft-light;
  opacity: 0.55;
  pointer-events: none;
}
.debug-ui::after {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(82, 230, 255, 0.9), transparent);
  opacity: 0.0;
  transform: translateX(-40%);
  animation: panelScanLine 3.6s ease-in-out infinite;
  pointer-events: none;
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

.context-menu {
  position: fixed;
  z-index: 1003;
  background:
    radial-gradient(circle at 0 0, rgba(72, 221, 255, 0.25), transparent 55%),
    radial-gradient(circle at 100% 100%, rgba(255, 116, 207, 0.2), transparent 55%),
    linear-gradient(160deg, #111111 0%, #1c1c1c 40%, #202020 100%);
  border-radius: 16px;
  padding: 6px 0 8px;
  color: #e8e8e8;
  min-width: 172px;
  box-shadow:
    0 0 0 1px rgba(0,0,0,0.8),
    0 14px 40px rgba(0,0,0,0.7),
    0 0 22px rgba(72, 221, 255, 0.2);
  border: 1px solid rgba(255,255,255,0.08);
  font-size: 13.5px;
  pointer-events: auto;
  user-select: none;
  backdrop-filter: blur(12px);
  overflow: hidden;
}
.context-menu::before {
  content: "";
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  border: 1px solid rgba(255,255,255,0.03);
  background-image:
    linear-gradient(90deg, rgba(255,255,255,0.04) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255,255,255,0.035) 1px, transparent 1px);
  background-size: 16px 16px;
  mix-blend-mode: soft-light;
  opacity: 0.55;
  pointer-events: none;
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

.cute-panel {
  position: relative;
  background:
    radial-gradient(circle at 0 0, rgba(72, 221, 255, 0.16), transparent 55%),
    radial-gradient(circle at 100% 100%, rgba(255, 116, 207, 0.16), transparent 55%),
    linear-gradient(160deg, #121212 0%, #191919 45%, #202020 100%);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 18px;
  box-shadow:
    0 0 0 1px rgba(0,0,0,0.85),
    0 18px 52px rgba(0,0,0,0.7),
    0 0 26px rgba(90, 230, 255, 0.18);
  color: #e8e8e8;
  pointer-events: auto;
  backdrop-filter: blur(12px);
  overflow: hidden;
}
.cute-panel::before {
  content: "";
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  border: 1px solid rgba(255,255,255,0.03);
  background-image:
    linear-gradient(90deg, rgba(255,255,255,0.04) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255,255,255,0.035) 1px, transparent 1px);
  background-size: 18px 18px;
  mix-blend-mode: soft-light;
  opacity: 0.5;
  pointer-events: none;
}
.cute-panel::after {
  content: "";
  position: absolute;
  inset: 8px;
  border-radius: 14px;
  border: 1px solid rgba(255,255,255,0.04);
  opacity: 0.35;
  pointer-events: none;
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
.history-panel select option { background: #2a2a2a; color: #fff; }
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
  animation: panelAppear 0.24s cubic-bezier(0.34,1.56,0.64,1) both;
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
.h2 { font-size: 13px; color: rgba(255,255,255,0.7); font-weight: 600; }
.msg-wrapper { display: flex; flex-direction: column; }
.msg-notice  { align-self: center; margin: 6px 0; text-align: center; max-width: 90%; }
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
.msg-time { font-size: 10px; opacity: 0.45; align-self: flex-end; }
.incoming .msg-time { align-self: flex-start; }
@keyframes chatAnimation {
  from { opacity: 0; transform: translateY(5px); }
  to   { opacity: 1; transform: translateY(0);   }
}

/* ===== 科幻启动遮罩 ===== */
.luna-intro-mask {
  position: fixed;
  inset: 0;
  z-index: 9000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.92);
  backdrop-filter: blur(6px);
  pointer-events: none;
  font-family: "Courier New", "Consolas", monospace;
}

.luna-boot-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  width: 460px;
  position: relative;
  padding: 40px 32px;
  border: 1px solid rgba(0, 255, 200, 0.18);
  border-radius: 4px;
  box-shadow:
    0 0 40px rgba(0, 255, 200, 0.08),
    inset 0 0 60px rgba(0, 255, 200, 0.03);
}

/* 扫描线 */
.scan-line {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(0, 255, 200, 0.7), transparent);
  animation: scanMove 2.4s ease-in-out infinite;
  box-shadow: 0 0 10px rgba(0, 255, 200, 0.5);
}
@keyframes scanMove {
  0%   { top: 0%;   opacity: 0.9; }
  50%  { top: 100%; opacity: 0.6; }
  100% { top: 0%;   opacity: 0.9; }
}

/* 主标题 */
.boot-title {
  display: flex;
  align-items: baseline;
  gap: 6px;
  letter-spacing: 0.18em;
}
.boot-bracket {
  font-size: 28px;
  color: rgba(0, 255, 200, 0.5);
  font-weight: 300;
}
.boot-name {
  font-size: 36px;
  font-weight: 700;
  color: rgba(0, 255, 200, 0.95);
  text-shadow:
    0 0 12px rgba(0, 255, 200, 0.7),
    0 0 30px rgba(0, 255, 200, 0.3);
  letter-spacing: 0.3em;
  animation: titlePulse 3s ease-in-out infinite;
}
.boot-version {
  font-size: 11px;
  color: rgba(0, 255, 200, 0.4);
  letter-spacing: 0.12em;
  align-self: flex-end;
  margin-bottom: 4px;
}
@keyframes titlePulse {
  0%, 100% { text-shadow: 0 0 12px rgba(0,255,200,0.7), 0 0 30px rgba(0,255,200,0.3); }
  50%       { text-shadow: 0 0 20px rgba(0,255,200,0.95), 0 0 50px rgba(0,255,200,0.5); }
}

/* 副标题 */
.boot-subtitle {
  font-size: 10px;
  letter-spacing: 0.22em;
  color: rgba(0, 255, 200, 0.45);
  animation: subtitleBlink 2s step-end infinite;
}
@keyframes subtitleBlink {
  0%, 90%, 100% { opacity: 1; }
  95%            { opacity: 0; }
}

/* 进度条 */
.boot-bar-wrap {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}
.boot-bar-track {
  width: 100%;
  height: 3px;
  background: rgba(0, 255, 200, 0.08);
  border-radius: 2px;
  overflow: hidden;
  border: 1px solid rgba(0, 255, 200, 0.12);
}
.boot-bar-fill {
  height: 100%;
  width: 0%;
  background: linear-gradient(90deg, rgba(0,255,200,0.5), rgba(0,255,200,0.95));
  box-shadow: 0 0 8px rgba(0, 255, 200, 0.6);
  animation: barFill 2.8s cubic-bezier(0.4, 0, 0.2, 1) forwards;
}
@keyframes barFill {
  0%   { width: 0%;   }
  30%  { width: 28%;  }
  55%  { width: 61%;  }
  75%  { width: 79%;  }
  90%  { width: 93%;  }
  100% { width: 100%; }
}
.boot-bar-pct {
  font-size: 9px;
  color: rgba(0, 255, 200, 0.4);
  letter-spacing: 0.15em;
  animation: pctCount 2.8s linear forwards;
}
@keyframes pctCount {
  0%   { opacity: 0.4; }
  100% { opacity: 0.8; }
}

/* 滚动日志 */
.boot-log {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.log-line {
  font-size: 11px;
  color: rgba(0, 255, 200, 0.55);
  letter-spacing: 0.05em;
  opacity: 0;
  animation: logFadeIn 0.3s ease forwards;
  display: flex;
  gap: 6px;
}
.log-tag {
  color: rgba(0, 255, 200, 0.3);
  flex-shrink: 0;
}
@keyframes logFadeIn {
  from { opacity: 0; transform: translateX(-6px); }
  to   { opacity: 1; transform: translateX(0); }
}

/* 底部十六进制装饰 */
.boot-footer {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}
.boot-hex {
  font-size: 9px;
  color: rgba(0, 255, 200, 0.20);
  letter-spacing: 0.08em;
  transition: color 0.18s ease;
}

/* 过渡动画 */
.luna-intro-enter-active { transition: opacity 0.4s ease; }
.luna-intro-leave-active { transition: opacity 0.8s ease; }
.luna-intro-enter-from   { opacity: 0; }
.luna-intro-leave-to     { opacity: 0; }

.particle-canvas-overlay {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  z-index: 8999;
  pointer-events: none;
  pointer-events: none;
}

@keyframes panelScanLine {
  0% {
    opacity: 0;
    transform: translateX(-40%);
  }
  20% {
    opacity: 0.9;
    transform: translateX(0%);
  }
  60% {
    opacity: 0.2;
    transform: translateX(40%);
  }
  100% {
    opacity: 0;
    transform: translateX(60%);
  }
}

@keyframes panelAppear {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.97);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
</style>