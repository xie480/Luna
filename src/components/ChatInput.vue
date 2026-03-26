<template>
  <div 
    class="chat-bar-wrapper" 
    :style="wrapperStyle"
    @mouseenter="$emit('mouseenter')" 
    @mouseleave="$emit('mouseleave')"
  >
    <div class="drag-handle" @mousedown="startDrag" title="长按拖动">
      <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="9" cy="12" r="1" fill="currentColor"></circle>
        <circle cx="15" cy="12" r="1" fill="currentColor"></circle>
        <circle cx="9" cy="5" r="1" fill="currentColor"></circle>
        <circle cx="15" cy="5" r="1" fill="currentColor"></circle>
        <circle cx="9" cy="19" r="1" fill="currentColor"></circle>
        <circle cx="15" cy="19" r="1" fill="currentColor"></circle>
      </svg>
    </div>

    <button class="icon-btn settings-btn" @click="$emit('open-settings')" title="设置">
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="3"></circle>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
      </svg>
    </button>

    <button class="icon-btn plan-btn" @click="$emit('toggle-plan')" title="计划执行">
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 6h18"></path>
        <path d="M3 12h12"></path>
        <path d="M3 18h8"></path>
        <circle cx="18" cy="12" r="3"></circle>
        <path d="M18 9v-2"></path>
      </svg>
    </button>

    <button class="icon-btn query-btn" @click="$emit('toggle-query')" title="数据查询">
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="7"></circle>
        <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
      </svg>
    </button>

    <button class="icon-btn history-btn" @click="$emit('toggle-history')" title="历史记录">
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
        <line x1="16" y1="2" x2="16" y2="6"></line>
        <line x1="8" y1="2" x2="8" y2="6"></line>
        <line x1="3" y1="10" x2="21" y2="10"></line>
      </svg>
    </button>

    <div class="bar-divider"></div>

    <div class="input-container">
      <input 
        ref="inputRef"
        v-model="inputText" 
        type="text" 
        :placeholder="showOverlay ? '' : 'Message LUNA...'" 
        @keydown.enter="sendMessage"
        @focus="isFocused = true"
        @blur="isFocused = false"
        :disabled="loading || streaming"
        :class="{ 'hidden-text': showOverlay }"
      />
      
      <transition name="fade-overlay">
        <div v-if="showOverlay" class="stream-overlay">
          <div class="glitch-container">
            <span class="glitch-text" :data-text="overlayText">
              {{ overlayText }}
            </span>
            <span class="cursor-blink">_</span>
          </div>
          <div class="scan-line"></div>
        </div>
      </transition>
    </div>

    <div class="emotion-indicator" :style="emotionStyle" :title="'当前情绪: ' + (currentEmotion || 'neutral')"></div>

    <button 
      class="send-btn" 
      :class="{ 'is-active': loading || streaming }"
      @click="sendMessage" 
      :disabled="loading || streaming || !inputText"
    >
      <transition name="icon-scale" mode="out-in">
        <span v-if="!loading && !streaming" key="idle" class="btn-text">SEND</span>
        <div v-else-if="loading" key="loading" class="cyber-loader">
          <div class="cyber-ring"></div>
          <div class="cyber-core"></div>
        </div>
        <div v-else-if="streaming" key="streaming" class="streaming-icon">
          <div class="wave-bar"></div>
          <div class="wave-bar"></div>
          <div class="wave-bar"></div>
        </div>
      </transition>
    </button>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, reactive } from 'vue';

const props = defineProps(['loading', 'streaming', 'streamText', 'currentEmotion', 'statusText']);
const emit = defineEmits(['send', 'open-settings', 'toggle-history', 'toggle-query', 'toggle-plan', 'mouseenter', 'mouseleave', 'close']);

const inputText = ref("");
const inputRef = ref(null);
const isFocused = ref(false);

const pos = reactive({ x: 0, y: 0 });
const isCustomPos = ref(false);
let dragStart = { x: 0, y: 0 };
let initialPos = { x: 0, y: 0 };
let dragging = false;

onMounted(() => {
  nextTick(() => {
    inputRef.value?.focus();
  });
});

onBeforeUnmount(() => {
  stopDrag();
});

function startDrag(e) {
  e.preventDefault();
  if (!isCustomPos.value) {
    const el = document.querySelector('.chat-bar-wrapper');
    if (el) {
      const rect = el.getBoundingClientRect();
      pos.x = rect.left;
      pos.y = rect.top;
      isCustomPos.value = true;
    }
  }
  dragging = true;
  dragStart = { x: e.clientX, y: e.clientY };
  initialPos = { x: pos.x, y: pos.y };
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}
function onDrag(e) {
  if (!dragging) return;
  const dx = e.clientX - dragStart.x;
  const dy = e.clientY - dragStart.y;
  pos.x = initialPos.x + dx;
  pos.y = initialPos.y + dy;
}
function stopDrag() {
  dragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}
const wrapperStyle = computed(() => {
  if (isCustomPos.value) {
    return { left: pos.x + 'px', top: pos.y + 'px', bottom: 'auto', transform: 'none' };
  }
  return {};
});
function sendMessage() {
  if (!inputText.value.trim() || props.loading || props.streaming) return;
  emit('send', inputText.value);
  inputText.value = "";
}

const trimmedStatusText = computed(() => String(props.statusText || '').trim());

const showOverlay = computed(() => {
  if (isFocused.value) return false;
  return props.streaming || props.loading || (!!trimmedStatusText.value && !inputText.value);
});
const overlayText = computed(() => {
  if (props.streaming) return props.streamText || 'LUNA_CORE: DECRYPTING...';
  if (props.loading) return trimmedStatusText.value || 'LUNA_CORE: PROCESSING...';
  return trimmedStatusText.value;
});

const EMOTION_MAP = {
  angry: { color: '#ff2a2a', speed: '0.8s', intensity: '0 0 15px' },
  annoyed: { color: '#ff5500', speed: '1.2s', intensity: '0 0 10px' },
  irritated: { color: '#ff5500', speed: '1.0s', intensity: '0 0 10px' },
  tsundere: { color: '#ff0055', speed: '1.5s', intensity: '0 0 12px' },
  sad: { color: '#4a90e2', speed: '3s', intensity: '0 0 8px' },
  lonely: { color: '#5065a5', speed: '3.5s', intensity: '0 0 6px' },
  despair: { color: '#2c3e50', speed: '4s', intensity: '0 0 5px' },
  broken: { color: '#000000', speed: '5s', intensity: '0 0 2px' },
  smile: { color: '#ffd700', speed: '2s', intensity: '0 0 12px' },
  happy: { color: '#ffaa00', speed: '1.5s', intensity: '0 0 14px' },
  affectionate: { color: '#ff69b4', speed: '2s', intensity: '0 0 15px' },
  hopeful: { color: '#00ffc8', speed: '2.5s', intensity: '0 0 12px' },
  fearful: { color: '#8e44ad', speed: '0.5s', intensity: '0 0 8px' },
  anxious: { color: '#9b59b6', speed: '0.6s', intensity: '0 0 8px' },
  uneasy: { color: '#a569bd', speed: '1s', intensity: '0 0 8px' },
  neutral: { color: '#00ffc8', speed: '3s', intensity: '0 0 8px' },
  default: { color: '#00ffc8', speed: '3s', intensity: '0 0 8px' }
};

const emotionStyle = computed(() => {
  const key = (props.currentEmotion || 'neutral').toLowerCase();
  const em = EMOTION_MAP[key] || EMOTION_MAP.default;
  return {
    backgroundColor: em.color,
    boxShadow: em.intensity + ' ' + em.color,
    animationDuration: em.speed
  };
});
</script>

<style scoped>
.chat-bar-wrapper {
  position: fixed;
  bottom: 64px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--bg-panel, linear-gradient(160deg, rgba(8,14,26,0.97), rgba(5,10,18,0.97)));
  padding: 10px 16px;
  border-radius: 40px;
  border: 1px solid var(--border, rgba(0,255,200,0.16));
  backdrop-filter: blur(24px) saturate(160%);
  box-shadow: var(--shadow-panel, 0 4px 28px rgba(0,0,0,0.65));
  z-index: 9000;
  transition: border-color 0.3s, box-shadow 0.3s;
}
.chat-bar-wrapper:hover {
  border-color: color-mix(in oklab, var(--primary, #00ffc8) 52%, transparent);
  box-shadow: var(--shadow-panel, 0 6px 36px rgba(0,0,0,0.7)), var(--glow-primary, 0 0 18px rgba(0,255,200,0.07));
}

.drag-handle {
  cursor: grab;
  color: color-mix(in oklab, var(--primary, #00ffc8) 42%, transparent);
  display: flex;
  align-items: center;
  padding: 5px 2px;
  margin-right: 2px;
  transition: color 0.2s;
}
.drag-handle:hover { color: color-mix(in oklab, var(--primary, #00ffc8) 90%, transparent); }
.drag-handle:active { cursor: grabbing; }

.bar-divider {
  width: 1px;
  height: 20px;
  background: linear-gradient(180deg, transparent, color-mix(in oklab, var(--primary, #00ffc8) 32%, transparent), transparent);
  flex-shrink: 0;
  margin: 0 6px;
}

.icon-btn {
  background: none;
  border: none;
  color: color-mix(in oklab, var(--primary, #00ffc8) 62%, transparent);
  cursor: pointer;
  padding: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: color 0.18s, background 0.18s, transform 0.15s;
}
.icon-btn:hover {
  color: color-mix(in oklab, var(--primary, #00ffc8) 94%, white 6%);
  background: var(--hover, rgba(0,255,200,0.08));
  transform: translateY(-1px);
}

.input-container {
  position: relative;
  display: flex;
  align-items: center;
  overflow: hidden;
  border-radius: 22px;
  margin: 0 5px;
}
input {
  background: rgba(0,0,0,0.22);
  border: 1px solid color-mix(in oklab, var(--border, rgba(0,255,200,0.1)) 70%, transparent);
  color: var(--text-main, rgba(232,255,248,0.88));
  padding: 10px 18px;
  width: clamp(360px, 34vw, 560px);
  border-radius: 22px;
  outline: none;
  font-size: 14px;
  letter-spacing: 0.2px;
  transition: border-color 0.25s, background 0.25s, box-shadow 0.25s;
  pointer-events: auto;
}
input::placeholder {
  color: color-mix(in oklab, var(--primary, #00ffc8) 28%, transparent);
  font-size: 12px;
  letter-spacing: 0.5px;
}
input:focus {
  border-color: color-mix(in oklab, var(--primary, #00ffc8) 52%, transparent);
  background: rgba(0,0,0,0.32);
  box-shadow: 0 0 0 3px color-mix(in oklab, var(--primary, #00ffc8) 18%, transparent);
}
input:disabled { opacity: 0.4; cursor: not-allowed; }
input.hidden-text { color: transparent; }

.stream-overlay {
  position: absolute;
  top: 0; left: 0;
  width: 100%; height: 100%;
  background: rgba(0,0,0,0.88);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  padding-left: 16px;
  z-index: 10;
  border-radius: 22px;
  pointer-events: none;
}
.glitch-container {
  display: flex;
  align-items: center;
  overflow: hidden;
  white-space: nowrap;
  max-width: calc(clamp(360px, 34vw, 560px) - 30px);
}
.glitch-text {
  font-family: "Courier New", monospace;
  font-size: 11px;
  font-weight: 600;
  color: var(--primary, #00ffc8);
  letter-spacing: 0.8px;
  animation: text-flicker 2s infinite;
}
.cursor-blink {
  color: var(--primary, #00ffc8);
  margin-left: 2px;
  font-weight: bold;
  animation: blink 1s step-end infinite;
}
.scan-line {
  position: absolute;
  top: 0; left: 0;
  width: 100%; height: 1px;
  background: linear-gradient(90deg, transparent, var(--primary, #00ffc8), transparent);
  opacity: 0.35;
  animation: scan 2.4s linear infinite;
}

.emotion-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin: 0 4px;
  animation: breathe infinite ease-in-out;
  transition: background-color 0.8s ease, box-shadow 0.8s ease;
}

.send-btn {
  position: relative;
  background: var(--primary, #00ffc8);
  color: #000;
  border: none;
  padding: 0;
  border-radius: 18px;
  cursor: pointer;
  transition: all 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
  width: 66px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  margin-left: 4px;
  box-shadow: 0 2px 12px color-mix(in oklab, var(--primary, #00ffc8) 32%, transparent);
}
.send-btn:hover:not(:disabled) {
  filter: brightness(1.1);
  transform: translateY(-1px);
  box-shadow: 0 4px 18px color-mix(in oklab, var(--primary, #00ffc8) 40%, transparent);
}
.send-btn:disabled { opacity: 0.32; cursor: not-allowed; box-shadow: none; }
.send-btn.is-active {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgba(0,0,0,0.5);
  border: 1px solid color-mix(in oklab, var(--primary, #00ffc8) 32%, transparent);
  box-shadow: 0 0 10px color-mix(in oklab, var(--primary, #00ffc8) 20%, transparent);
}
.btn-text { font-weight: 700; font-size: 10px; letter-spacing: 1px; }

.cyber-loader {
  position: relative;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cyber-ring {
  position: absolute;
  width: 100%;
  height: 100%;
  border: 1.5px solid transparent;
  border-top-color: var(--primary);
  border-right-color: var(--primary);
  border-radius: 50%;
  animation: cyber-spin 0.75s linear infinite;
}
.cyber-core {
  width: 5px;
  height: 5px;
  background: var(--primary);
  border-radius: 50%;
  box-shadow: 0 0 6px var(--primary);
  animation: core-pulse 1.5s ease-in-out infinite;
}
.streaming-icon {
  display: flex;
  gap: 2px;
  align-items: center;
  justify-content: center;
  height: 14px;
}
.wave-bar {
  width: 2px;
  background: var(--primary);
  border-radius: 2px;
  animation: wave-sharp 0.8s ease-in-out infinite;
}
.wave-bar:nth-child(1) { height: 5px; animation-delay: 0s; }
.wave-bar:nth-child(2) { height: 11px; animation-delay: 0.15s; }
.wave-bar:nth-child(3) { height: 5px; animation-delay: 0.3s; }

@keyframes cyber-spin { to { transform: rotate(360deg); } }
@keyframes core-pulse {
  0%, 100% { opacity: 0.5; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1.2); }
}
@keyframes wave-sharp {
  0%, 100% { height: 3px; opacity: 0.5; }
  50% { height: 13px; opacity: 1; }
}
.icon-scale-enter-active, .icon-scale-leave-active { transition: all 0.25s cubic-bezier(0.34, 1.56, 0.64, 1); }
.icon-scale-enter-from { opacity: 0; transform: scale(0.5) rotate(-90deg); }
.icon-scale-leave-to { opacity: 0; transform: scale(0.5) rotate(90deg); }
@keyframes scan {
  0% { top: 0%; opacity: 0; }
  10% { opacity: 0.4; }
  90% { opacity: 0.4; }
  100% { top: 100%; opacity: 0; }
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
@keyframes text-flicker {
  0% { opacity: 0.2; } 2% { opacity: 1; } 8% { opacity: 0.2; }
  9% { opacity: 1; } 20% { opacity: 1; } 30% { opacity: 0.4; }
  70% { opacity: 0.9; } 100% { opacity: 0.9; }
}
@keyframes breathe {
  0%, 100% { opacity: 0.25; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.25); }
}
.fade-overlay-enter-active, .fade-overlay-leave-active { transition: opacity 0.25s ease; }
.fade-overlay-enter-from, .fade-overlay-leave-to { opacity: 0; }

@media (max-width: 900px) {
  .chat-bar-wrapper { padding: 8px 12px; }
  input { width: clamp(240px, 52vw, 380px); padding: 9px 14px; }
}
</style>
