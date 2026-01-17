<template>
  <div class="gal-page">
    <div class="stage">
      <div
        class="character-frame"
        :class="[
          { switching: isSwitching },
          'emotion-' + currentEmotion
        ]"
        @animationend="onSwitchEnd"
      >
        <div class="character-position">
          <img
            ref="characterImg"
            class="character-img"
            :src="largeImage"
            alt="Luna"
            @error="onImageError"
          />
        </div>
      </div>
    </div>

    <div class="dialogue-wrap">
      <div class="dialogue-inner">
        <div class="name-row">
          <div class="char-name">Luna</div>
          <div class="confidence" v-if="lastReply.confidence !== undefined">
            置信度：{{ formatConfidence(lastReply.confidence) }}
          </div>
        </div>

        <div class="dialogue-text">
          <template v-if="lastReply.loading">
            <div class="typing">
              <span class="dot" v-for="n in 3" :key="n"></span>
              <span class="label">Luna正在思考…</span>
            </div>
          </template>
          <template v-else>
            <div class="text" v-html="lastReply.displayedText"></div>
          </template>
        </div>

        <div class="input-row">
          <input
            class="input"
            v-model="input"
            @keydown.enter.prevent="onSend"
            :disabled="sending"
            placeholder="对Luna说点什么……（按 Enter 发送）"
          />
          <button
            class="send-btn"
            @click="onSend"
            :disabled="sending || !input.trim()"
          >
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from "vue";
import {
  chat as chatApi,
  startup as startupApi,
  shutdown as shutdownApi
} from "../../api/index.js";

/* ---------- 情绪与立绘 ---------- */
const EMOTIONS = [
  "Angry",
  "Despair",
  "Fearful",
  "Shocked",
  "Shy",
  "Smile",
  "Soft",
  "Solemn",
];

function randVariant() {
  return Math.floor(Math.random() * 4) + 1;
}

function pickImageForEmotion(emotion) {
  const e = EMOTIONS.includes(emotion) ? emotion : "Soft";
  return new URL(`../../assets/pic/${e}${randVariant()}.png`, import.meta.url)
    .href;
}

/* ---------- 立绘切换动画 ---------- */
const largeImage = ref(pickImageForEmotion("Soft"));
const isSwitching = ref(false);
const pendingImage = ref(null);
const animationDuration = 600;

function animateSwitchToEmotion(emotion) {
  const newImg = pickImageForEmotion(emotion);
  if (largeImage.value === newImg) return;

  if (isSwitching.value) {
    pendingImage.value = newImg;
    return;
  }

  isSwitching.value = true;
  pendingImage.value = newImg;

  setTimeout(() => {
    if (pendingImage.value) {
      largeImage.value = pendingImage.value;
      pendingImage.value = null;
    }
  }, animationDuration / 2);
}

function onSwitchEnd() {
  isSwitching.value = false;
}

function onImageError() {
  largeImage.value = pickImageForEmotion("Soft");
}

/* ---------- 对话状态 ---------- */
const input = ref("");
const sending = ref(false);
const currentEmotion = ref("Soft");

const lastReply = reactive({
  fullText: "",
  displayedText: "",
  loading: false,
  emotion: "Soft",
  confidence: undefined,
});

/* ---------- 打字机效果 ---------- */
let typeTimer = null;
const typingInterval = 24;

function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function startTypewriter(full) {
  clearInterval(typeTimer);
  lastReply.displayedText = "";
  let i = 0;

  typeTimer = setInterval(() => {
    i++;
    lastReply.displayedText = escapeHtml(full.slice(0, i)).replace(
      /\n/g,
      "<br/>"
    );
    if (i >= full.length) {
      clearInterval(typeTimer);
      typeTimer = null;
    }
  }, typingInterval);
}

/* ---------- 模型回复统一处理 ---------- */
function handleModelReply(res) {
  const parsed = typeof res === "string" ? JSON.parse(res) : res;

  lastReply.loading = false;
  lastReply.fullText = parsed.reply;
  lastReply.emotion = parsed.emotion;
  lastReply.confidence = parsed.confidence;

  currentEmotion.value = parsed.emotion;
  animateSwitchToEmotion(parsed.emotion);
  startTypewriter(parsed.reply);
}

function handleNetworkError() {
  lastReply.loading = false;
  currentEmotion.value = "Soft";
  animateSwitchToEmotion("Soft");
  startTypewriter("网络错误，请稍后再试。");
}

/* ---------- 发送用户消息 ---------- */
async function onSend() {
  if (sending.value) return;

  const text = input.value.trim();
  if (!text) return;

  sending.value = true;
  input.value = "";
  lastReply.loading = true;

  try {
    const res = await chatApi({ userInput: text });
    handleModelReply(res);
  } catch (e) {
    console.error("发送消息失败", e);
    handleNetworkError();
  } finally {
    sending.value = false;
    await nextTick();
  }
}

/* ---------- 启动 ---------- */
async function callStartup() {
  lastReply.loading = true;
  try {
    const res = await startupApi();
    handleModelReply(res);
  } catch (e) {
    console.error("启动失败", e);
    handleNetworkError();
  } finally {
    await nextTick();
  }
}

/* ---------- 关机 ---------- */
window.addEventListener("beforeunload", () => {
  if (window.desktopApi) {
    try { window.desktopApi.shutdown(); } catch(e){}
  }
  // 尝试通知后端 shutdown API
  try { shutdownApi(); } catch(e){}
});

/* ---------- 生命周期 ---------- */
onMounted(() => {
  callStartup();
});
</script>

<style scoped>
/* 使用视口单位和 clamp() 使所有组件随着窗口缩放 */
:root {
  --stage-width: 72vw;
  --stage-height: 60vh;
  --baseline-offset: 12vh; /* 立绘的基线下移，可调整 */
}

.gal-page {
  position: relative;
  height: 100vh;
  overflow: hidden;
  font-family: "PingFang SC", "Microsoft Yahei", -apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial;
  background: transparent; /* 背景图已移除，若需要可在父容器设置 */
}

/* 将舞台尺寸基于视口，不使用固定 px */
.stage {
  position: absolute;
  left: 50%;
  top: 6vh;
  transform: translateX(-50%);
  width: var(--stage-width);
  height: var(--stage-height);
  max-width: 94vw;
  max-height: 82vh;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2;
  pointer-events: none;
}

/* Idle 呼吸动画（基于 vh 单位） */
.character-frame:not(.switching) .character-img {
  animation: idleBreath 4.2s ease-in-out infinite;
}

@keyframes idleBreath {
  0% { transform: translateY(0vh) scale(1); }
  50% { transform: translateY(-1.2vh) scale(1.01); }
  100% { transform: translateY(0vh) scale(1); }
}

.character-frame {
  perspective: 1400px;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: end;
  justify-content: center;
}

.character-position {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: end;
  justify-content: center;
  transform: translateY(var(--baseline-offset));
  transition: transform 240ms ease;
}

/* 立绘使用相对大小（随舞台变化） */
.character-img {
  width: min(70vw, 120%);
  height: auto;
  max-height: 78vh;
  object-fit: contain;
  transform-origin: center bottom;
  transition: transform 200ms ease, opacity 200ms ease;
  box-shadow: 0 6vh 6vh rgba(0,0,0,0.18);
  border-radius: 6px;
  backface-visibility: hidden;
  transform-style: preserve-3d;
}

/* 切换动画（使用 vh 替代固定 px） */
.character-frame.switching .character-img {
  animation: switchAnim 600ms cubic-bezier(.2,.9,.3,1) both;
  will-change: transform, opacity;
}

@keyframes switchAnim {
  0% { transform: translateY(0vh) rotateY(0deg) scale(1); opacity: 1; }
  25% { transform: translateY(-3vh) rotateY(65deg) scale(1.02); opacity: 1; }
  48% { transform: translateY(-4vh) rotateY(170deg) scale(1.035); opacity: 0; }
  52% { transform: translateY(-4vh) rotateY(190deg) scale(1.03); opacity: 0; }
  75% { transform: translateY(-2.5vh) rotateY(250deg) scale(1.01); opacity: 0.85; }
  100% { transform: translateY(0vh) rotateY(360deg) scale(1); opacity: 1; }
}

/* 底部对话框：宽度基于视口并随之缩放 */
.dialogue-wrap {
  position: absolute;
  left: 50%;
  bottom: 2vh;
  transform: translateX(-50%);
  width: 90vw;
  max-width: 1100px;
  z-index: 3;
  pointer-events: auto;
}

.dialogue-inner {
  background: rgba(12,12,14,0.55);
  backdrop-filter: blur(6px);
  color: #fff;
  border-radius: 12px;
  padding: clamp(12px, 1.2vh, 20px);
  box-shadow: 0 1.2vh 3vh rgba(0,0,0,0.35);
}

/* 响应式字体 */
.char-name { font-weight:700; font-size: clamp(14px, 1.6vw, 18px); }
.confidence { font-size: clamp(12px, 1.2vw, 14px); color: #d7e8ff; }

.dialogue-text { min-height: 6vh; margin-bottom: 1vh; }
.text { font-size: clamp(14px, 1.6vw, 16px); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.typing { display:flex; align-items:center; gap:10px; font-size: clamp(13px, 1.4vw, 15px); color: #e6eefc; }
.typing .dot { width: 8px; height: 8px; border-radius: 50%; background: #fff; opacity: 0.25; animation: typ 1s infinite; }
.typing .dot:nth-child(2) { animation-delay: 0.15s }
.typing .dot:nth-child(3) { animation-delay: 0.3s }
@keyframes typ { 0%{ transform: translateY(0); opacity:0.25 } 50%{ transform: translateY(-6px); opacity:1 } 100%{ transform: translateY(0); opacity:0.25 } }

.input-row { display:flex; gap:8px; align-items:center; }
.input { flex:1; padding: clamp(8px, 1vh, 12px) clamp(12px, 1.2vw, 16px); border-radius:999px; border:1px solid rgba(255,255,255,0.12); background: rgba(255,255,255,0.04); color:#fff; outline:none; font-size: clamp(13px, 1.4vw, 15px); }
.input::placeholder { color: rgba(255,255,255,0.6); }
.send-btn { padding: clamp(6px, 0.8vh, 10px) clamp(10px, 1vw, 14px); border-radius:999px; background: linear-gradient(180deg,#ff8aa3,#ff5a86); border:none; color:white; font-weight:600; cursor:pointer; font-size: clamp(13px, 1.4vw, 15px); }
.send-btn:disabled { opacity:0.5; cursor:not-allowed; }

/* 响应式断点 */
@media (max-width: 900px) {
  .stage { width: 94vw; top: 4vh; height: calc(100vh - 26vh); }
  .dialogue-wrap { bottom: 1.2vh; width: 96vw; }
  .character-img { max-height: 66vh; }
}

/* 特定情绪动画示例：Fearful 与 Smile 保持，但基于相对位移 */
.emotion-Fearful:not(.switching) .character-img {
  animation: idleBreath 4.2s ease-in-out infinite, fearfulShake 0.9s ease-in-out infinite;
}
@keyframes fearfulShake { 0% { transform: translateY(0vh) scale(0.985) translateX(0) } 25% { transform: translateY(0vh) scale(0.985) translateX(-0.5vh) } 50% { transform: translateY(0vh) scale(0.985) translateX(0.5vh) } 75% { transform: translateY(0vh) scale(0.985) translateX(-0.3vh) } 100% { transform: translateY(0vh) scale(0.985) translateX(0) } }

.emotion-Smile:not(.switching) .character-img { animation: smileFloat 3.6s ease-in-out infinite; }
@keyframes smileFloat { 0% { transform: translateY(0vh) scale(1) } 50% { transform: translateY(-1.6vh) scale(1.015) } 100% { transform: translateY(0vh) scale(1) } }

</style>
