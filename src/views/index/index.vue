<template>
  <div class="app-root">

    <div v-if="isSetupMode" class="top-banner" @mouseenter="uiEnter" @mouseleave="uiLeave">
      正在进行初始位置设定，请调整模型位置和大小。
    </div>
    <div v-if="isTrackingSetupMode" class="top-banner" @mouseenter="uiEnter" @mouseleave="uiLeave">
      正在进行滑鼠追蹤設定，請點擊模型對應的位置設置為跟蹤點。
    </div>

    <transition name="fade">
      <div v-if="appearance.appearanceHint.value" class="toast-hint">
        {{ appearance.appearanceHint.value }}
      </div>
    </transition>

    <transition name="login-fade">
      <div v-if="loginVisible" class="login-mask">
        <div
          class="login-terminal"
          :class="{
            collapsed: loginCollapsed,
            'login-error-state': !!loginError,
            'login-success-state': loginSuccess && !loginError
          }"
          @mouseenter="uiEnter"
          @mouseleave="uiLeave"
        >
          <div class="login-header">
            <div class="login-header-left">
              <span class="login-title">LUNA / AUTH</span>
              <span class="login-status" :class="{ ok: !loginError }">
                {{ loginError ? "鑒權失敗" : "等待登錄" }}
              </span>
            </div>
            <div class="login-header-actions">
              <button class="login-header-btn close" @click="exitApp" title="退出應用">×</button>
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
                <span v-if="!loginLoading">登錄並啟動 LUNA</span>
                <span v-else>正在驗證憑證…</span>
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

    <div
      ref="dummyBoxRef"
      class="css-chat-bubble"
      style="position: absolute; visibility: hidden; pointer-events: none; top: -9999px; left: -9999px; width: fit-content; max-width: 280px;"
    ></div>

    <transition name="fade">
      <ChatInput
        v-if="showChat"
        :loading="isConnecting"
        :streaming="isStreaming"
        :streamText="streamText"
        :currentEmotion="currentEmotion"
        :statusText="lunaStatus"
        @send="onSend"
        @open-settings="openSettings"
        @toggle-history="toggleHistory"
        @toggle-query="toggleQuery"
        @toggle-plan="togglePlan"
        @close="showChat = false"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      />
    </transition>

    <transition name="fade">
      <HistoryPanel
        v-if="showHistory"
        ref="historyPanelRef"
        @close="showHistory = false"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      />
    </transition>

    <transition name="fade">
      <QueryPanel
        v-if="showQuery"
        @close="showQuery = false"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      />
    </transition>

    <transition name="fade">
      <PlanPanel
        v-if="showPlan"
        :runtime="planRuntime"
        :asyncEvents="planAsyncEvents"
        @close="showPlan = false"
        @run-created="handlePlanCreated"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      />
    </transition>

    <transition name="fade">
      <SettingsPanel
        v-if="showSettings"
        :core="getCoreModel()"
        :model="model"
        :appearance="appearance"
        :rhythm="rhythm"
        :isLoggedIn="loginSuccess"
        :isSetupMode="isSetupMode"
        :isTrackingSetupMode="isTrackingSetupMode"
        :isModelVisible="modelVisible"
        @close="showSettings = false"
        @reset-model="resetModelState"
        @toggle-setup="toggleSetupMode"
        @toggle-tracking-setup="toggleTrackingSetupMode"
        @reset-setup="resetModelTransform"
        @reset-tracking-setup="resetTrackingOrigin"
        @toggle-model="modelVisible = !modelVisible"
        @logout="handleLogout"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      />
    </transition>

    <transition name="fade">
      <ApprovalModal
        v-if="showApproval"
        :task="approvalTask"
        @approve="onApprove(true)"
        @reject="onApprove(false)"
        @mouseenter="uiEnter"
        @mouseleave="uiLeave"
      />
    </transition>

    <div ref="wrapperRef" class="interactive-wrapper">
      <canvas ref="canvasRef" @contextmenu.prevent></canvas>
    </div>

    <transition name="luna-intro">
      <div v-if="lunaIntroVisible" class="luna-intro-mask">
        <div class="luna-boot-screen">
          <div class="scan-line"></div>
          <div class="boot-title">
            <span class="boot-bracket">[</span>
            <span class="boot-name">LUNA</span>
            <span class="boot-bracket">]</span>
            <span class="boot-version">v2.0.1</span>
          </div>
          <div class="boot-subtitle">AI 助手核心模塊 · 啟動中</div>
          <div class="boot-bar-wrap">
            <div class="boot-bar-track">
              <div class="boot-bar-fill"></div>
            </div>
            <span class="boot-bar-pct">正在加載系統…</span>
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

  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick, watch, computed } from "vue";
import { gsap } from "gsap";
import * as PIXI from "pixi.js";

import {
  chat as chatApi,
  startup as startupApi,
  shutdown as shutdownApi,
  login as loginApi,
  logout as logoutApi,
} from "../../api/index.js";
import { EMOTION_EXPRESSIONS } from "../../utils/emotion-expressions";

import { useBubble } from "../../composables/useBubble.js";
import { useAppearance } from "../../composables/useAppearance.js";
import { useRhythm } from "../../composables/useRhythm.js";
import { useTheme } from "../../composables/useTheme.js";
import { usePlanGraphStore } from "../../composables/usePlanGraphStore.js";

import ChatInput from "../../components/ChatInput.vue";
import SettingsPanel from "../../components/SettingsPanel.vue";
import HistoryPanel from "../../components/HistoryPanel.vue";
import QueryPanel from "../../components/QueryPanel.vue";
import PlanPanel from "../../components/PlanPanel.vue";
import ApprovalModal from "../../components/ApprovalModal.vue";

const canvasRef = ref(null);
const wrapperRef = ref(null);

const bgParticlesVisible = ref(true);
const showChat = ref(false);
const showSettings = ref(false);
const showHistory = ref(false);
const showQuery = ref(false);
const showPlan = ref(false);
const historyPanelRef = ref(null);
const trackingEnabled = ref(true);
const lunaIntroVisible = ref(false);
const lunaStatus = ref("");

const planStore = usePlanGraphStore();
const planRuntime = computed(() => planStore.toPlanPanelRuntime());
const planAsyncEvents = computed(() => planStore.asyncEvents.value);

let activePlanId = "";
let planReconnectTimer = null;
let planReconnectAttempt = 0;
const PLAN_RECONNECT_STEPS = [1000, 2000, 5000, 10000, 30000];

async function handlePlanCreated({ planId }) {
  if (!planId) return;
  activePlanId = planId;
  sessionStorage.setItem("luna:current-plan-id", planId);
  planReconnectAttempt = 0;
  clearTimeout(planReconnectTimer);

  planStore.reset(planId);

  try {
    await planStore.syncGraphSnapshot(planId);
    appearance.showAppearanceHint("已加载计划图谱快照");
  } catch (e) {
    console.error("[Plan] 首次快照拉取失败:", e);
    appearance.showAppearanceHint("计划快照拉取失败，将自动重试");
    schedulePlanSnapshotReconnect();
  }
}

function schedulePlanSnapshotReconnect() {
  if (!activePlanId) return;
  if (planReconnectTimer) return;

  const delay = PLAN_RECONNECT_STEPS[Math.min(planReconnectAttempt, PLAN_RECONNECT_STEPS.length - 1)];
  planReconnectAttempt += 1;

  planReconnectTimer = setTimeout(async () => {
    planReconnectTimer = null;
    try {
      await planStore.syncGraphSnapshot(activePlanId);
      planReconnectAttempt = 0;
      appearance.showAppearanceHint("计划图谱已自动校准");
    } catch (e) {
      console.error("[Plan] 自动校准失败:", e);
      schedulePlanSnapshotReconnect();
    }
  }, delay);
}

const showApproval = ref(false);
const approvalTask = ref(null);

const { loadTheme } = useTheme();

const STATUS_MIN_DISPLAY_MS = 1000;
const statusQueue = [];
let isConsumingStatusQueue = false;
let statusLastEnqueued = "";
let statusConsumeVersion = 0;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function normalizeStatusPayload(data) {
  if (!data) return "";
  if (typeof data === "string") return data.trim();

  if (typeof data === "object") {
    if (typeof data.msg === "string" && data.msg.trim()) return data.msg.trim();
    if (typeof data.message === "string" && data.message.trim()) return data.message.trim();
    if (typeof data.status === "string" && data.status.trim()) return data.status.trim();
    return "";
  }

  return "";
}

function clearStatusDisplay() {
  statusQueue.length = 0;
  statusLastEnqueued = "";
  statusConsumeVersion++;
  isConsumingStatusQueue = false;
  lunaStatus.value = "";
}

function enqueueStatusMessage(msg) {
  const text = normalizeStatusPayload(msg);
  if (!text) return;

  if (text === statusLastEnqueued) return;
  statusLastEnqueued = text;

  statusQueue.push(text);

  if (!isConsumingStatusQueue) {
    consumeStatusQueue();
  }
}

async function consumeStatusQueue() {
  if (isConsumingStatusQueue) return;
  isConsumingStatusQueue = true;
  const myVersion = statusConsumeVersion;

  try {
    while (statusQueue.length > 0) {
      if (myVersion !== statusConsumeVersion) break;
      const nextStatus = statusQueue.shift();
      lunaStatus.value = nextStatus;
      await sleep(STATUS_MIN_DISPLAY_MS);
      if (myVersion !== statusConsumeVersion) break;
    }
  } finally {
    if (myVersion === statusConsumeVersion) {
      isConsumingStatusQueue = false;
    }
  }
}

function normalizeSseEventPayload(raw) {
  if (raw && typeof raw === "object" && Object.prototype.hasOwnProperty.call(raw, "event")) {
    return {
      event: raw.event || "message",
      data: raw.data,
    };
  }

  if (raw && typeof raw === "object" && raw.type === "APPROVAL_REQUEST") {
    return {
      event: "APPROVAL_REQUEST",
      data: raw.payload || raw,
    };
  }

  return {
    event: "luna-status",
    data: raw,
  };
}

function toUpperEventType(event, data) {
  const e1 = String(event || "").trim();
  if (e1 && e1 !== "message") return e1.toUpperCase();
  const e2 = String(data?.eventType || data?.type || "").trim();
  return e2.toUpperCase();
}

const PLAN_EVENTS = new Set([
  "PLAN_CREATED",
  "PLAN_PHASE_STARTED",
  "PLAN_PHASE_FINISHED",
  "PLAN_NODE_RUNNING",
  "PLAN_NODE_SUCCESS",
  "PLAN_NODE_FAILED",
  "PLAN_FINISHED",
  "PLAN_REPORT_READY",
  "APPROVAL_RESULT",
  "SKILL_ASYNC_RESULT",
]);

const isSetupMode = ref(false);
const isTrackingSetupMode = ref(false);

const TRANSFORM_KEY = "luna:transform";
const TRACKING_ORIGIN_KEY = "luna:tracking-origin";
const LUNA_VISIBLE_KEY = "luna:visible";

let trackingOriginOffset = { x: 0, y: 0 };
let trackingMarker = null;

const modelVisible = ref(localStorage.getItem(LUNA_VISIBLE_KEY) !== "false");

const loginVisible = ref(true);
const loginLoading = ref(false);
const loginError = ref("");
const loginSuccess = ref(false);
const loginForm = ref({ username: "", password: "" });
const loginSessionId = ref(Math.random().toString(16).slice(2, 10).toUpperCase());
const loginLogLines = ref([
  "正在建立與鑒權服務的安全連接…",
  "檢測到當前會話未認證。",
  "請輸入用戶名與密碼以繼續。",
]);
const authToken = ref("");
const loginCollapsed = ref(false);

function exitApp() {
  window.desktopApi?.quit?.();
}

const bootLines = [
  "正在初始化神經接口…",
  "正在加載 Live2D 核心模塊…",
  "正在掛載表情合成引擎…",
  "正在校準視線追蹤參數…",
  "正在連接 Luna 對話服務…",
  "正在預熱語言模型核心…",
  "系統已就緒，等待指令。",
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

async function performLogin() {
  if (!loginForm.value.username || !loginForm.value.password) {
    loginError.value = "用戶名或密碼不能為空";
    return;
  }
  loginLoading.value = true;
  loginError.value = "";
  loginSuccess.value = false;
  loginLogLines.value.push("正在向鑒權服務發送憑證…");
  try {
    const data = await loginApi({
      username: loginForm.value.username,
      password: loginForm.value.password,
    });
    const token = data?.token || "";
    if (!token) {
      loginError.value = "鑒權服務未返回有效 Token";
      loginLogLines.value.push("鑒權失敗：Token 缺失。");
      return;
    }
    authToken.value = token;
    loginSuccess.value = true;
    loginLogLines.value.push("鑒權通過，正在啟動 LUNA 核心…");

    overUI = false;
    updatePetState();

    loginVisible.value = false;
    setTimeout(() => {
      startBootSequence();
    }, 450);

    const cachedPlanId = sessionStorage.getItem("luna:current-plan-id");
    if (cachedPlanId) {
      activePlanId = cachedPlanId;
      planStore.reset(cachedPlanId);
      planStore.syncGraphSnapshot(cachedPlanId).catch(() => {});
    }
  } catch (e) {
    console.error("[Auth] 登錄請求失敗", e);
    loginError.value = "無法連接鑒權服務，請檢查網絡或服務状态";
    loginLogLines.value.push("網絡錯誤：無法連接到鑒權端點。");
  } finally {
    loginLoading.value = false;
  }
}

function onLoginSubmit() {
  if (!loginLoading.value) performLogin();
}

async function handleLogout() {
  try {
    await logoutApi(authToken.value);
  } catch (e) {
    console.error("[Auth] 登出請求失敗", e);
  } finally {
    authToken.value = "";
    loginSuccess.value = false;
    loginVisible.value = true;
    showSettings.value = false;
    showChat.value = false;
    showHistory.value = false;
    showQuery.value = false;
    showPlan.value = false;
    showApproval.value = false;
    approvalTask.value = null;
    activePlanId = "";
    sessionStorage.removeItem("luna:current-plan-id");
    planStore.reset("");

    overUI = true;
    overModel = false;
    updatePetState();
    refreshUiInteractivity();

    if (model) {
      gsap.to(model, {
        alpha: 0,
        y: (app?.renderer?.height || window.innerHeight) + 60,
        duration: 0.8,
        ease: "power2.in",
      });
    }

    loginLogLines.value = [
      "會話已終止。",
      "請重新輸入憑證以建立連接。",
    ];
    loginError.value = "";
    loginForm.value.password = "";
  }
}

let app = null;
let container = null;
let model = null;
const expressionCache = new Map();

const dummyBoxRef = ref(null);
const alwaysShowBubbles = ref(false);
const { chatBubbles, bubbleAnchor, registerBubble, sendReplyAsBubbles, splitReplyIntoChunks } = useBubble(dummyBoxRef, alwaysShowBubbles);

bubbleAnchor.value = { x: window.innerWidth / 2, y: window.innerHeight - 150 };

const appearance = useAppearance();
const rhythm = useRhythm();

function getCoreModel() { return model?.internalModel?.coreModel ?? null; }

const isConnecting = ref(false);
const isStreaming = ref(false);
const streamText = ref("");

function normalizeResponse(res) {
  const data = res?.data ?? res;
  if (typeof data === "string") {
    try { return JSON.parse(data); } catch { return data; }
  }
  return data;
}

async function playDecryptionEffect(text) {
  streamText.value = "";
  const displayLen = Math.min(text.length, 60);

  for (let i = 0; i < displayLen; i++) {
    streamText.value += text[i];
    await new Promise((r) => setTimeout(r, 20 + Math.random() * 30));
  }

  if (text.length > 60) {
    streamText.value += "...";
  }

  await new Promise((r) => setTimeout(r, 300));
}

async function handleModelReply(res) {
  if (!res) throw new Error("Empty response");

  let replyText = "";
  let em = "";

  if (typeof res === "string") {
    replyText = res;
  } else {
    replyText = res.reply || res.text || res.message || res.content || res.answer || res.data || "";
    em = res.emotion || "";
    if (!replyText && typeof res === "object") {
      replyText = JSON.stringify(res);
    }
  }

  if (!replyText) throw new Error("No text content found in response");

  if (em) {
    currentEmotion.value = em;
    try { applyEmotionExpressions(em); } catch {}
  }

  if (showHistory.value && historyPanelRef.value) {
    historyPanelRef.value.refresh();
  }

  const chunks = splitReplyIntoChunks(replyText);
  const previewText = chunks.length > 0 ? chunks[0] : replyText;

  const effectPromise = playDecryptionEffect(previewText);
  const bubblePromise = sendReplyAsBubbles(replyText, { interval: 1000, duration: 5000 });

  await Promise.all([effectPromise, bubblePromise]);
}

function handleNetworkError() {
  appearance.showAppearanceHint("網絡請求失敗");
}

async function onSend(text) {
  if (isConnecting.value || isStreaming.value) return;
  if (!text) return;

  isConnecting.value = true;
  streamText.value = "";

  if (showHistory.value && historyPanelRef.value) {
    historyPanelRef.value.pushMessage({
      sender: "user",
      content: text,
      timestamp: Date.now(),
    });
  }

  try {
    const minLoadTime = new Promise((resolve) => setTimeout(resolve, 800));

    const [res] = await Promise.all([
      chatApi({ userInput: text }),
      minLoadTime,
    ]);

    isConnecting.value = false;
    isStreaming.value = true;
    streamText.value = "";

    await handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 發送失敗", e);
    handleNetworkError();
  } finally {
    isConnecting.value = false;
    isStreaming.value = false;
    streamText.value = "";
    lunaStatus.value = "";
  }
}

async function onApprove(approved) {
  if (!approvalTask.value) return;

  const currentTaskId = approvalTask.value.taskId;

  showApproval.value = false;
  approvalTask.value = null;
  uiLeave();

  try {
    const res = await window.mcpApi.approveSkill({
      taskId: currentTaskId,
      approved,
    });

    if (res && typeof res === "object" && (res.reply || res.text || res.message || res.content || res.answer || res.data)) {
      isStreaming.value = true;
      streamText.value = "";
      await handleModelReply(normalizeResponse(res));
    } else {
      appearance.showAppearanceHint(approved ? "已同意操作" : "已拒絕操作");
    }
  } catch (e) {
    console.error("[Approval] Failed:", e);
    appearance.showAppearanceHint("審批提交失敗");
  } finally {
    isStreaming.value = false;
    streamText.value = "";
  }
}

async function callStartup() {
  isConnecting.value = true;
  try {
    const res = await startupApi();
    isConnecting.value = false;
    handleModelReply(normalizeResponse(res));
  } catch (e) {
    console.error("[Luna] 啟動失敗", e);
    isConnecting.value = false;
    handleNetworkError();
  }
}

async function callShutdown() {
  isConnecting.value = true;
  try {
    const res = await shutdownApi();
    isConnecting.value = false;
    handleModelReply(normalizeResponse(res));
  } catch (e) {
    isConnecting.value = false;
  }
}

let overUI = false;
let overModel = false;

function updatePetState() {
  if (overModel || overUI) window.pet?.enter();
  else window.pet?.leave();
}

let uiLeaveTimer = null;

function hasBlockingUiPanels() {
  return (
    showSettings.value ||
    showHistory.value ||
    showQuery.value ||
    showApproval.value ||
    lunaIntroVisible.value
  );
}

function getUiHoverSelector() {
  return [
    ".chat-bar-wrapper:not(.fade-leave-active):hover",
    ".settings-panel:not(.fade-leave-active):hover",
    ".login-terminal:hover",
    ".history-panel:not(.fade-leave-active):hover",
    ".query-panel:not(.fade-leave-active):hover",
    ".plan-panel-shell:not(.fade-leave-active) .plan-panel:hover",
    ".top-banner:hover",
    ".modal:hover",
    ".approval-mask:hover",
    ".luna-intro-mask:hover",
  ].join(", ");
}

function isAnyUiHovered() {
  return !!document.querySelector(getUiHoverSelector());
}

function refreshUiInteractivity() {
  clearTimeout(uiLeaveTimer);

  if (hasBlockingUiPanels()) {
    overUI = true;
    updatePetState();
    return;
  }

  if (showChat.value || showPlan.value) {
    uiLeaveTimer = setTimeout(() => {
      overUI = isAnyUiHovered();
      updatePetState();
    }, 80);
    return;
  }

  uiLeave();
}

function uiEnter() {
  clearTimeout(uiLeaveTimer);
  overUI = true;
  updatePetState();
}

function uiLeave() {
  clearTimeout(uiLeaveTimer);
  uiLeaveTimer = setTimeout(() => {
    if (hasBlockingUiPanels()) {
      overUI = true;
      updatePetState();
      return;
    }

    if (showChat.value || showPlan.value) {
      overUI = isAnyUiHovered();
      updatePetState();
      return;
    }

    overUI = isAnyUiHovered();
    updatePetState();
  }, 150);
}

function onWindowMouseMove() {
  if (hasBlockingUiPanels()) return;
  if (!showChat.value && !showPlan.value) return;

  const hovered = isAnyUiHovered();
  if (hovered !== overUI) {
    overUI = hovered;
    updatePetState();
  }
}

let modelLeaveTimer = null;

function modelEnter() {
  clearTimeout(modelLeaveTimer);
  overModel = true;
  updatePetState();
}

function modelLeave() {
  clearTimeout(modelLeaveTimer);
  modelLeaveTimer = setTimeout(() => {
    overModel = false;
    updatePetState();
  }, 150);
}

watch(showChat, () => refreshUiInteractivity());
watch(showSettings, () => refreshUiInteractivity());
watch(showHistory, () => refreshUiInteractivity());
watch(showQuery, () => refreshUiInteractivity());
watch(showPlan, () => refreshUiInteractivity());
watch(showApproval, () => refreshUiInteractivity());
watch(loginVisible, () => refreshUiInteractivity());

watch(lunaIntroVisible, (val) => {
  if (!val) {
    uiLeave();
  } else {
    refreshUiInteractivity();
  }
});

watch(modelVisible, (val) => {
  localStorage.setItem(LUNA_VISIBLE_KEY, val);
  if (container) {
    container.visible = val;
  }
  if (!val) {
    overModel = false;
    updatePetState();
  }
});

function toggleSetupMode() {
  if (isTrackingSetupMode.value) isTrackingSetupMode.value = false;
  isSetupMode.value = !isSetupMode.value;
  if (!isSetupMode.value) {
    saveModelTransform();
  }
}

function saveModelTransform() {
  if (!container) return;
  const data = { x: container.x, y: container.y, scale: container.scale.x };
  localStorage.setItem(TRANSFORM_KEY, JSON.stringify(data));
}

function loadModelTransform() {
  const raw = localStorage.getItem(TRANSFORM_KEY);
  if (raw && container) {
    try {
      const data = JSON.parse(raw);
      if (typeof data.x === "number" && !isNaN(data.x)) container.x = data.x;
      if (typeof data.y === "number" && !isNaN(data.y)) container.y = data.y;
      if (typeof data.scale === "number" && !isNaN(data.scale) && data.scale > 0) {
        container.scale.set(data.scale);
      }
    } catch {}
  }
}

function resetModelTransform() {
  if (!container) {
    appearance.showAppearanceHint("容器未初始化");
    return;
  }

  container.x = 0;
  container.y = 0;
  container.scale.set(1);
  container.visible = true;
  modelVisible.value = true;

  if (model && app) {
    model.alpha = 1;
    model.visible = true;
    model.x = app.renderer.width / 2;
    model.y = app.renderer.height || window.innerHeight;
    model.scale.set(0.2);
  }

  saveModelTransform();
  appearance.showAppearanceHint("模型状态已强制重置！");
}

function toggleTrackingSetupMode() {
  if (isSetupMode.value) isSetupMode.value = false;
  isTrackingSetupMode.value = !isTrackingSetupMode.value;
  if (isTrackingSetupMode.value) {
    drawTrackingMarker();
  } else {
    if (trackingMarker) trackingMarker.visible = false;
    localStorage.setItem(TRACKING_ORIGIN_KEY, JSON.stringify(trackingOriginOffset));
  }
}

function resetTrackingOrigin() {
  trackingOriginOffset = { x: 0, y: 0 };
  localStorage.setItem(TRACKING_ORIGIN_KEY, JSON.stringify(trackingOriginOffset));
  if (isTrackingSetupMode.value) {
    drawTrackingMarker();
  }
  appearance.showAppearanceHint("追蹤中心已重置");
}

function drawTrackingMarker() {
  if (!container) return;
  if (!trackingMarker) {
    trackingMarker = new PIXI.Graphics();
    container.addChild(trackingMarker);
  }
  trackingMarker.clear();
  trackingMarker.beginFill(0xff0000);
  trackingMarker.drawCircle(0, 0, 10);
  trackingMarker.endFill();
  trackingMarker.position.set(trackingOriginOffset.x, trackingOriginOffset.y);
  trackingMarker.visible = true;
}

let dragging = false;
let lastPos = { x: 0, y: 0 };

function onPointerDown(e) {
  if (e.button !== 0) return;

  if (isTrackingSetupMode.value) {
    const localPoint = container.toLocal(e.global);
    trackingOriginOffset = { x: localPoint.x, y: localPoint.y };
    drawTrackingMarker();
    return;
  }

  dragging = true;
  lastPos = { x: e.global.x, y: e.global.y };
}

function onPointerMove(e) {
  if (!dragging) return;
  const dx = e.global.x - lastPos.x;
  const dy = e.global.y - lastPos.y;
  lastPos = { x: e.global.x, y: e.global.y };
  container.x += dx;
  container.y += dy;
}

function onPointerUp() {
  dragging = false;
}

function onWheel(ev) {
  if (!model || !app) return;
  if (!overModel) return;

  const rect = canvasRef.value.getBoundingClientRect();
  const globalPoint = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);

  ev.preventDefault();
  const factor = ev.deltaY > 0 ? 0.95 : 1.05;
  const newScale = Math.min(10, Math.max(0.05, container.scale.x * factor));
  const localPoint = container.toLocal(globalPoint, app.stage);
  container.scale.set(newScale);
  const newGlobal = container.toGlobal(localPoint);
  container.position.x += globalPoint.x - newGlobal.x;
  container.position.y += globalPoint.y - newGlobal.y;
}

const PARAM_CONFIG = {
  HEAD_X: { param: "ParamAngleX", range: [-30, 30] },
  HEAD_Y: { param: "ParamAngleY", range: [-30, 30] },
  EYE_X: { param: "ParamEyeBallX", range: [-1, 1] },
  EYE_Y: { param: "ParamEyeBallY", range: [-1, 1] },
  BREATH: { param: "ParamBreath", range: [0, 1] },
};

function applyLookAt(dx, dy) {
  const core = getCoreModel();
  if (!core) return;

  const targetX = dx - trackingOriginOffset.x;
  const targetY = dy - trackingOriginOffset.y;

  const nx = Math.max(-1, Math.min(1, targetX / (app.renderer.width / 2)));
  const ny = -Math.max(-1, Math.min(1, targetY / (app.renderer.height / 2)));
  const mapRange = (v, [min, max]) => min + ((v + 1) / 2) * (max - min);
  try {
    core.setParameterValueById(PARAM_CONFIG.EYE_X.param, mapRange(nx, PARAM_CONFIG.EYE_X.range));
    core.setParameterValueById(PARAM_CONFIG.EYE_Y.param, mapRange(ny, PARAM_CONFIG.EYE_Y.range));
    core.setParameterValueById(PARAM_CONFIG.HEAD_X.param, mapRange(nx, PARAM_CONFIG.HEAD_X.range));
    core.setParameterValueById(PARAM_CONFIG.HEAD_Y.param, mapRange(ny, PARAM_CONFIG.HEAD_Y.range));
  } catch {}
}

function onGlobalPointerMove(ev) {
  if (!trackingEnabled.value || !model || !modelVisible.value) return;
  const rect = canvasRef.value.getBoundingClientRect();
  const world = new PIXI.Point(ev.clientX - rect.left, ev.clientY - rect.top);
  const local = container.toLocal(world, app.stage);
  applyLookAt(local.x, local.y);
}

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
    } catch {}
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
  await appearance.applyAllEnabled(getCoreModel());
}

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

        const text = await res.text();
        if (text.trim().startsWith("<")) {
          throw new Error("文件未找到 (返回了 HTML)");
        }

        expressionCache.set(name, JSON.parse(text));
      } catch {}
    }),
  );
}

async function resetModelState() {
  const core = getCoreModel();
  if (!core) return;
  for (const f in appearance.appearanceAppliedMeta) {
    appearance.removeAppearanceFile(f, core);
  }
  await resetToSolemn();
  await appearance.applyAllEnabled(core);
  appearance.showAppearanceHint("模型表情已重置");
}

function openSettings() {
  showSettings.value = true;
}

function toggleHistory() {
  showHistory.value = !showHistory.value;
  if (showHistory.value) uiEnter();
}

function toggleQuery() {
  showQuery.value = !showQuery.value;
  if (showQuery.value) uiEnter();
}

function togglePlan() {
  showPlan.value = !showPlan.value;
  if (showPlan.value) {
    uiEnter();
    if (activePlanId) {
      planStore.syncGraphSnapshot(activePlanId).catch(() => {});
    }
  }
}

function waitForModelReady(timeout = 10000) {
  return new Promise((resolve) => {
    const start = performance.now();
    (function poll() {
      if (model?.internalModel?.coreModel) return resolve(true);
      if (performance.now() - start > timeout) return resolve(false);
      setTimeout(poll, 120);
    }());
  });
}

async function startBootSequence() {
  lunaIntroVisible.value = true;

  gsap.delayedCall(4.5, async () => {
    lunaIntroVisible.value = false;
    bgParticlesVisible.value = false;
    uiLeave();

    if (!model) {
      await waitForModelReady(10000);
    }

    if (model && app) {
      model.alpha = 0;
      model.y = (app.renderer.height || window.innerHeight) + 40;

      gsap.to(model, {
        alpha: 1,
        y: app.renderer.height || window.innerHeight,
        duration: 1.2,
        ease: "power3.out",
      });
    }
  });

  await applyEmotionExpressions(INITIAL_EMOTION);
  await callStartup();
}

onMounted(async () => {
  loadTheme();

  window.PIXI = PIXI;
  const { Live2DModel } = await import("pixi-live2d-display/cubism4");

  if (Live2DModel.registerTicker) {
    Live2DModel.registerTicker(PIXI.Ticker);
  }

  app = new PIXI.Application({
    view: canvasRef.value,
    backgroundAlpha: 0,
    resizeTo: window,
    resolution: window.devicePixelRatio || 1,
    autoDensity: true,
  });

  container = new PIXI.Container();
  container.visible = modelVisible.value;
  app.stage.addChild(container);

  wrapperRef.value.addEventListener("pointermove", onGlobalPointerMove);
  wrapperRef.value.addEventListener("wheel", onWheel, { passive: false });
  window.addEventListener("mousemove", onWindowMouseMove, true);

  if (window.pet && window.pet.onToggleChat) {
    window.pet.onToggleChat(() => {
      if (loginVisible.value) return;
      showChat.value = !showChat.value;

      if (!showChat.value) {
        showSettings.value = false;
        showHistory.value = false;
        showQuery.value = false;
        showPlan.value = false;
        refreshUiInteractivity();
      } else {
        overUI = true;
        updatePetState();
        setTimeout(() => {
          refreshUiInteractivity();
        }, 120);
      }
    });
  }

  if (window.desktopApi && window.desktopApi.onStatusUpdate) {
    window.desktopApi.onStatusUpdate((rawPayload) => {
      const { event, data } = normalizeSseEventPayload(rawPayload);
      const eventType = toUpperEventType(event, data);

      if (eventType === "APPROVAL_REQUEST") {
        const task = data?.payload ? data.payload : data;
        approvalTask.value = task || null;
        showApproval.value = !!approvalTask.value;
        if (showApproval.value) {
          uiEnter();
          appearance.showAppearanceHint("收到敏感操作審批請求");
        }

        planStore.applyEvent({
          ...(data || {}),
          eventType: "APPROVAL_REQUEST",
          planId: data?.planId || activePlanId || "",
        });
        return;
      }

      if (PLAN_EVENTS.has(eventType)) {
        planStore.applyEvent({
          ...(data || {}),
          eventType,
        });

        if (eventType === "PLAN_CREATED" && data?.planId) {
          activePlanId = data.planId;
          sessionStorage.setItem("luna:current-plan-id", activePlanId);
        }

        if (eventType === "PLAN_FINISHED" && (data?.planId || activePlanId)) {
          const pid = data?.planId || activePlanId;
          planStore.lockPlanFinalState(pid);
        }

        if ((eventType === "PLAN_NODE_FAILED" || eventType === "PLAN_PHASE_FINISHED" || eventType === "PLAN_REPORT_READY" || eventType === "PLAN_FINISHED") && activePlanId) {
          planStore.syncGraphSnapshot(activePlanId).catch(() => {
            schedulePlanSnapshotReconnect();
          });
        }

        if (eventType === "SKILL_ASYNC_RESULT") {
          const ok = !!data?.success;
          appearance.showAppearanceHint(ok ? "异步技能完成" : "异步技能失败");
        }
      }

      const msg = normalizeStatusPayload(data);
      if (!msg) {
        clearStatusDisplay();
        return;
      }

      enqueueStatusMessage(msg);
    });
  }

  try {
    model = await Live2DModel.from(`/models/luna/${encodeURIComponent("jk盐.model3.json")}`, {
      autoInteract: false,
      autoUpdate: true,
      ticker: app.ticker,
    });

    model.scale.set(0.2);
    model.anchor.set(0.5, 1);
    model.x = app.renderer.width / 2;
    model.y = app.renderer.height || window.innerHeight;
    model.interactive = true;
    model.cursor = "pointer";

    if (!lunaIntroVisible.value && loginSuccess.value) {
      model.alpha = 1;
    } else {
      model.alpha = 0;
      model.y += 60;
    }

    model
      .on("pointerdown", onPointerDown)
      .on("pointermove", onPointerMove)
      .on("pointerup", onPointerUp)
      .on("pointerupoutside", onPointerUp);

    model.on("pointerover", modelEnter);
    model.on("pointerout", modelLeave);

    container.addChild(model);

    const savedOrigin = localStorage.getItem(TRACKING_ORIGIN_KEY);
    if (savedOrigin) {
      try { trackingOriginOffset = JSON.parse(savedOrigin); } catch {}
    }

    await waitForModelReady(5000);
    loadModelTransform();

    appearance.loadAppearanceState();

    await nextTick();
    await appearance.applyAllEnabled(getCoreModel());
    await applyEmotionExpressions(INITIAL_EMOTION);
  } catch (e) {
    console.error("[Live2D] 模型加载失败", e);
    appearance.showAppearanceHint("模型加载失败，请检查文件路径");
  }

  setTimeout(() => {
    if (model && loginSuccess.value && !lunaIntroVisible.value && model.alpha === 0) {
      model.alpha = 1;
      model.y = app.renderer.height || window.innerHeight;
    }
  }, 6000);

  await preloadExpressions();
  startBreath();

  const cachedPlanId = sessionStorage.getItem("luna:current-plan-id");
  if (cachedPlanId) {
    activePlanId = cachedPlanId;
    planStore.reset(cachedPlanId);
    planStore.syncGraphSnapshot(cachedPlanId).catch(() => {});
  }

  const onVisibility = () => {
    if (!document.hidden && activePlanId) {
      planStore.syncGraphSnapshot(activePlanId).catch(() => {});
    }
  };
  document.addEventListener("visibilitychange", onVisibility);

  onBeforeUnmount(() => {
    document.removeEventListener("visibilitychange", onVisibility);
  });
});

onBeforeUnmount(() => {
  stopBreath();
  rhythm.dispose(getCoreModel(), trackingEnabled);
  app?.destroy(true);
  callShutdown();

  window.removeEventListener("mousemove", onWindowMouseMove, true);

  statusQueue.length = 0;
  isConsumingStatusQueue = false;
  statusLastEnqueued = "";
  statusConsumeVersion = 0;

  if (planReconnectTimer) {
    clearTimeout(planReconnectTimer);
    planReconnectTimer = null;
  }
});
</script>

<style>
html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
}
</style>

<style scoped>
.app-root {
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
  background: transparent;
}

.top-banner {
  position: fixed;
  top: 0; left: 0; right: 0;
  background: rgba(0, 255, 200, 0.9);
  color: #000;
  text-align: center;
  padding: 8px;
  font-weight: bold;
  z-index: 9999;
  box-shadow: 0 2px 10px rgba(0,0,0,0.5);
}

.toast-hint {
  position: fixed;
  top: 60px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.85);
  color: var(--primary, #00ffc8);
  padding: 10px 24px;
  border-radius: 6px;
  border: 1px solid var(--primary, #00ffc8);
  z-index: 10000;
  font-size: 13px;
  font-weight: bold;
  pointer-events: none;
  box-shadow: 0 4px 15px rgba(0,0,0,0.5), 0 0 10px rgba(0,255,200,0.2);
  letter-spacing: 1px;
}

.login-mask {
  position: fixed;
  inset: 0;
  z-index: 9100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  font-family: "Courier New", "Consolas", monospace;
  pointer-events: none;
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
  pointer-events: auto;
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
.login-fade-leave-active {
  pointer-events: none;
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
  position: absolute;
  top: 0;
  left: 0;
  z-index: 1;
  pointer-events: none;
}
.interactive-wrapper canvas {
  display: block;
  width: 100vw;
  height: 100vh;
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
  z-index: 99999;
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
  50%      { transform: translateY(-3px); }
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
  font-size: 10px;
  letter-spacing: 0.22em;
  color: rgba(0, 255, 200, 0.45);
  align-self: flex-end;
  margin-bottom: 4px;
  animation: subtitleBlink 2s step-end infinite;
}
@keyframes subtitleBlink {
  0%, 90%, 100% { opacity: 1; }
  95%           { opacity: 0; }
}

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
  animation: barFill 4.2s cubic-bezier(0.4, 0, 0.2, 1) forwards;
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
  animation: pctCount 4.2s linear forwards;
}
@keyframes pctCount {
  0%   { opacity: 0.4; }
  100% { opacity: 0.8; }
}

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

.luna-intro-enter-active { transition: opacity 0.4s ease; }
.luna-intro-leave-active { transition: opacity 0.8s ease; pointer-events: none; }
.luna-intro-enter-from   { opacity: 0; }
.luna-intro-leave-to     { opacity: 0; }

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}
.fade-leave-active {
  pointer-events: none;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>
