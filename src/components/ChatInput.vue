<template>
  <div 
    class="chat-bar-wrapper" 
    :style="wrapperStyle"
    @mouseenter="$emit('mouseenter')" 
    @mouseleave="$emit('mouseleave')"
  >
    
    <!-- 拖動手柄 (新增) -->
    <div class="drag-handle" @mousedown="startDrag" title="長按拖動">
      <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="9" cy="12" r="1" fill="currentColor"></circle>
        <circle cx="15" cy="12" r="1" fill="currentColor"></circle>
        <circle cx="9" cy="5" r="1" fill="currentColor"></circle>
        <circle cx="15" cy="5" r="1" fill="currentColor"></circle>
        <circle cx="9" cy="19" r="1" fill="currentColor"></circle>
        <circle cx="15" cy="19" r="1" fill="currentColor"></circle>
      </svg>
    </div>

    <!-- 設置按鈕 -->
    <button class="icon-btn settings-btn" @click="$emit('open-settings')" title="設置">
      <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="3"></circle>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
      </svg>
    </button>

    <!-- 新增：查询中心按钮（在设置与历史中间） -->
    <button class="icon-btn query-btn" @click="$emit('toggle-query')" title="数据查询">
      <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="7"></circle>
        <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
      </svg>
    </button>

    <!-- 歷史記錄按鈕 -->
    <button class="icon-btn history-btn" @click="$emit('toggle-history')" title="歷史記錄">
      <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
        <line x1="16" y1="2" x2="16" y2="6"></line>
        <line x1="8" y1="2" x2="8" y2="6"></line>
        <line x1="3" y1="10" x2="21" y2="10"></line>
      </svg>
    </button>

    <div class="input-container">
      <input 
        ref="inputRef"
        v-model="inputText" 
        type="text" 
        :placeholder="showOverlay ? '' : 'Type a message...'" 
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

    <div class="emotion-indicator" :style="emotionStyle" :title="'當前情緒: ' + (currentEmotion || 'neutral')"></div>

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
import { ref, computed, onMounted, nextTick, reactive } from 'vue';

const props = defineProps(['loading', 'streaming', 'streamText', 'currentEmotion', 'statusText']);
const emit = defineEmits(['send', 'open-settings', 'toggle-history', 'toggle-query', 'mouseenter', 'mouseleave', 'close']);

const inputText = ref("");
const inputRef = ref(null);
const isFocused = ref(false);

const pos = reactive({ x: 0, y: 0 });
const isCustomPos = ref(false);
let dragStart = { x: 0, y: 0 };
let initialPos = { x: 0, y: 0 };

onMounted(() => {
  nextTick(() => {
    inputRef.value?.focus();
  });
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
  dragStart = { x: e.clientX, y: e.clientY };
  initialPos = { x: pos.x, y: pos.y };
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}
function onDrag(e) {
  const dx = e.clientX - dragStart.x;
  const dy = e.clientY - dragStart.y;
  pos.x = initialPos.x + dx;
  pos.y = initialPos.y + dy;
}
function stopDrag() {
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}
const wrapperStyle = computed(() => {
  if (isCustomPos.value) {
    return {
      left: pos.x + 'px',
      top: pos.y + 'px',
      bottom: 'auto',
      transform: 'none'
    };
  }
  return {};
});
function sendMessage() {
  if (!inputText.value.trim() || props.loading || props.streaming) return;
  emit('send', inputText.value);
  inputText.value = "";
}
const showOverlay = computed(() => {
  if (isFocused.value) return false;
  return props.streaming || props.loading || (props.statusText && !inputText.value);
});
const overlayText = computed(() => {
  if (props.streaming) return props.streamText || 'LUNA_CORE: DECRYPTING...';
  if (props.loading) return props.statusText || 'LUNA_CORE: PROCESSING...';
  return props.statusText || '';
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
/* 保持你原样式 */
.chat-bar-wrapper { position: fixed; bottom: 70px; left: 50%; transform: translateX(-50%); display: flex; align-items: center; gap: 12px; background: var(--bg-panel, rgba(5,10,19,0.9)); padding: 10px 20px; border-radius: 50px; border: 1px solid var(--border, rgba(0,255,200,0.3)); backdrop-filter: blur(12px); box-shadow: 0 10px 30px rgba(0,0,0,0.5); z-index: 9000; transition: box-shadow 0.3s ease; }
.drag-handle { cursor: grab; color: var(--primary); opacity: 0.5; display: flex; align-items: center; padding: 4px; margin-right: -4px; }
.drag-handle:hover { opacity: 1; }
.drag-handle:active { cursor: grabbing; }
.icon-btn { background: none; border: none; color: var(--primary); cursor: pointer; opacity: 0.7; transition: 0.2s; padding: 6px; display: flex; align-items: center; justify-content: center; border-radius: 50%; }
.icon-btn:hover { opacity: 1; background: var(--hover); transform: scale(1.1); }
.input-container { position: relative; display: flex; align-items: center; overflow: hidden; border-radius: 20px; }
input { background: rgba(0,0,0,0.3); border: 1px solid transparent; color: var(--text-main, #fff); padding: 8px 16px; width: 320px; border-radius: 20px; outline: none; transition: 0.3s; pointer-events: auto; }
input:focus { border-color: var(--primary); background: rgba(0,0,0,0.5); }
input:disabled { opacity: 0.5; cursor: not-allowed; }
input.hidden-text { color: transparent; }
.stream-overlay { position: absolute; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0, 0, 0, 0.85); backdrop-filter: blur(4px); display: flex; align-items: center; padding-left: 16px; z-index: 10; border-radius: 20px; pointer-events: none; }
.glitch-container { display: flex; align-items: center; overflow: hidden; white-space: nowrap; max-width: 280px; }
.glitch-text { font-family: "Courier New", monospace; font-size: 12px; font-weight: bold; color: var(--primary, #00ffc8); letter-spacing: 1px; position: relative; animation: text-flicker 2s infinite; }
.cursor-blink { color: var(--accent, #fff); margin-left: 4px; font-weight: bold; animation: blink 1s step-end infinite; }
.scan-line { position: absolute; top: 0; left: 0; width: 100%; height: 2px; background: var(--primary, #00ffc8); opacity: 0.6; box-shadow: 0 0 10px var(--primary, #00ffc8); animation: scan 2s linear infinite; }
.emotion-indicator { width: 8px; height: 8px; border-radius: 50%; z-index: 11; flex-shrink: 0; animation: breathe infinite ease-in-out; transition: background-color 0.8s ease, box-shadow 0.8s ease, animation-duration 0.8s ease; }
.send-btn { position: relative; background: var(--primary, #00ffc8); color: #000; border: none; padding: 0; border-radius: 20px; cursor: pointer; transition: all 0.5s cubic-bezier(0.34, 1.56, 0.64, 1); width: 64px; height: 34px; display: flex; align-items: center; justify-content: center; overflow: hidden; box-shadow: 0 0 0 rgba(0,255,200,0); }
.send-btn:hover:not(:disabled) { filter: brightness(1.1); transform: translateY(-1px); box-shadow: 0 4px 12px var(--hover); }
.send-btn:disabled { cursor: not-allowed; }
.send-btn.is-active { width: 34px; height: 34px; border-radius: 50%; background: rgba(0, 0, 0, 0.6); border: 1px solid var(--primary-dim); box-shadow: 0 0 15px var(--primary-dim); transform: scale(1.05); }
.btn-text { font-weight: bold; font-size: 12px; letter-spacing: 0.5px; }
.cyber-loader { position: relative; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; }
.cyber-ring { position: absolute; width: 100%; height: 100%; border: 2px solid transparent; border-top-color: var(--primary); border-right-color: var(--primary); border-radius: 50%; animation: cyber-spin 0.8s linear infinite; filter: drop-shadow(0 0 2px var(--primary)); }
.cyber-core { width: 6px; height: 6px; background: var(--accent, #fff); border-radius: 50%; box-shadow: 0 0 8px var(--primary); animation: core-pulse 1.5s ease-in-out infinite; }
.streaming-icon { display: flex; gap: 3px; align-items: center; justify-content: center; height: 14px; }
.wave-bar { width: 3px; background: var(--primary); border-radius: 2px; animation: wave-sharp 0.8s ease-in-out infinite; box-shadow: 0 0 5px var(--primary); }
.wave-bar:nth-child(1) { height: 6px; animation-delay: 0s; }
.wave-bar:nth-child(2) { height: 12px; animation-delay: 0.15s; }
.wave-bar:nth-child(3) { height: 6px; animation-delay: 0.3s; }
@keyframes cyber-spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
@keyframes core-pulse { 0%, 100% { opacity: 0.6; transform: scale(0.8); box-shadow: 0 0 4px var(--primary); } 50% { opacity: 1; transform: scale(1.2); box-shadow: 0 0 10px var(--primary); } }
@keyframes wave-sharp { 0%, 100% { height: 4px; opacity: 0.6; } 50% { height: 14px; opacity: 1; filter: brightness(1.3); } }
.icon-scale-enter-active, .icon-scale-leave-active { transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1); }
.icon-scale-enter-from { opacity: 0; transform: scale(0.5) rotate(-90deg); }
.icon-scale-leave-to { opacity: 0; transform: scale(0.5) rotate(90deg); }
@keyframes scan { 0% { top: 0%; opacity: 0; } 10% { opacity: 1; } 90% { opacity: 1; } 100% { top: 100%; opacity: 0; } }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
@keyframes text-flicker { 0% { opacity: 0.1; } 2% { opacity: 1; } 8% { opacity: 0.1; } 9% { opacity: 1; } 12% { opacity: 0.1; } 20% { opacity: 1; } 25% { opacity: 1; } 30% { opacity: 0.3; } 70% { opacity: 0.9; text-shadow: 2px 0 var(--accent, #fff); } 72% { opacity: 0.2; } 77% { opacity: 0.9; } 100% { opacity: 0.9; } }
@keyframes fadeIn { from { opacity: 0; transform: translateY(2px); } to { opacity: 1; transform: translateY(0); } }
@keyframes breathe { 0%, 100% { opacity: 0.3; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1.2); } }
.fade-overlay-enter-active, .fade-overlay-leave-active { transition: opacity 0.3s ease; }
.fade-overlay-enter-from, .fade-overlay-leave-to { opacity: 0; }
</style>
