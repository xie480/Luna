<template>
  <div class="chat-bar-wrapper" @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')">
    
    <!-- 設置按鈕 -->
    <button class="icon-btn settings-btn" @click="$emit('open-settings')" title="設置">
      <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="3"></circle>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
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

    <!-- 輸入框容器 -->
    <div class="input-container">
      <!-- 真實輸入框 -->
      <input 
        ref="inputRef"
        v-model="inputText" 
        type="text" 
        :placeholder="streaming ? '' : (statusText ? '' : 'Type a message...')" 
        @keydown.enter="sendMessage"
        :disabled="loading || streaming"
        :class="{ 'hidden-text': streaming }"
      />
      
      <!-- 狀態顯示層 (當有狀態文本且輸入框為空時顯示) -->
      <div v-if="statusText && !inputText && !streaming" class="status-overlay-text">
        <span class="status-text">{{ statusText }}</span>
      </div>

      <!-- SSE 流式傳輸特效層 (覆蓋在輸入框上) -->
      <transition name="fade-overlay">
        <div v-if="streaming" class="stream-overlay">
          <div class="glitch-container">
            <span class="glitch-text" :data-text="streamText || 'LUNA_CORE: PROCESSING...'">
              {{ streamText || 'LUNA_CORE: PROCESSING...' }}
            </span>
            <span class="cursor-blink">_</span>
          </div>
          <div class="scan-line"></div>
        </div>
      </transition>

      <!-- 呼吸燈情緒指示器 -->
      <div class="emotion-indicator" :style="emotionStyle"></div>
    </div>

    <!-- 發送按鈕 (狀態機) -->
    <button 
      class="send-btn" 
      :class="{ 'is-loading': loading, 'is-streaming': streaming }"
      @click="sendMessage" 
      :disabled="loading || streaming || !inputText"
    >
      <!-- 狀態 1: 正常發送圖標 -->
      <span v-if="!loading && !streaming" class="btn-content">SEND</span>

      <!-- 狀態 2: 科技感加載動畫 (等待響應) -->
      <div v-else-if="loading" class="tech-loader">
        <div class="orbit-ring outer"></div>
        <div class="orbit-ring inner"></div>
        <div class="core-dot"></div>
      </div>

      <!-- 狀態 3: 傳輸中 (鎖定/波形) -->
      <div v-else-if="streaming" class="streaming-icon">
        <div class="wave-bar"></div>
        <div class="wave-bar"></div>
        <div class="wave-bar"></div>
      </div>
    </button>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue';

// loading: 連接中 (按鈕轉圈)
// streaming: 接收數據中 (輸入框顯示特效)
// streamText: 特效層顯示的文字
const props = defineProps(['loading', 'streaming', 'streamText', 'currentEmotion', 'statusText']);
const emit = defineEmits(['send', 'open-settings', 'toggle-history', 'mouseenter', 'mouseleave', 'close']);

const inputText = ref("");
const inputRef = ref(null);

onMounted(() => {
  nextTick(() => {
    inputRef.value?.focus();
  });
});

function sendMessage() {
  if (!inputText.value.trim() || props.loading || props.streaming) return;
  emit('send', inputText.value);
  inputText.value = "";
}

// Emotion 映射表
const EMOTION_MAP = {
  Angry: { color: '#ff2a2a', speed: '0.8s', intensity: '0 0 15px' },
  Annoyed: { color: '#ff5500', speed: '1.2s', intensity: '0 0 10px' },
  Irritated: { color: '#ff5500', speed: '1.0s', intensity: '0 0 10px' },
  Tsundere: { color: '#ff0055', speed: '1.5s', intensity: '0 0 12px' },
  Sad: { color: '#4a90e2', speed: '3s', intensity: '0 0 8px' },
  Lonely: { color: '#5065a5', speed: '3.5s', intensity: '0 0 6px' },
  Despair: { color: '#2c3e50', speed: '4s', intensity: '0 0 5px' },
  Broken: { color: '#000000', speed: '5s', intensity: '0 0 2px' },
  Smile: { color: '#ffd700', speed: '2s', intensity: '0 0 12px' },
  Happy: { color: '#ffaa00', speed: '1.5s', intensity: '0 0 14px' },
  Affectionate: { color: '#ff69b4', speed: '2s', intensity: '0 0 15px' },
  Hopeful: { color: '#00ffc8', speed: '2.5s', intensity: '0 0 12px' },
  Fearful: { color: '#8e44ad', speed: '0.5s', intensity: '0 0 8px' },
  Anxious: { color: '#9b59b6', speed: '0.6s', intensity: '0 0 8px' },
  Uneasy: { color: '#a569bd', speed: '1s', intensity: '0 0 8px' },
  default: { color: '#00ffc8', speed: '3s', intensity: '0 0 8px' }
};

const emotionStyle = computed(() => {
  const em = EMOTION_MAP[props.currentEmotion] || EMOTION_MAP.default;
  return {
    backgroundColor: em.color,
    boxShadow: em.intensity + ' ' + em.color,
    animationDuration: em.speed
  };
});
</script>

<style scoped>
/* 基礎容器樣式 */
.chat-bar-wrapper {
  position: fixed;
  bottom: 80px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--bg-panel, rgba(5,10,19,0.9));
  padding: 10px 20px;
  border-radius: 50px;
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  backdrop-filter: blur(12px);
  box-shadow: 0 10px 30px rgba(0,0,0,0.5);
  z-index: 9000;
  transition: all 0.3s ease;
}

/* 圖標按鈕 */
.icon-btn {
  background: none;
  border: none;
  color: var(--primary);
  cursor: pointer;
  opacity: 0.7;
  transition: 0.2s;
  padding: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.icon-btn:hover { 
  opacity: 1; 
  background: var(--hover);
  transform: scale(1.1); 
}

/* 輸入框容器 */
.input-container {
  position: relative;
  display: flex;
  align-items: center;
  overflow: hidden; /* 確保掃描線不溢出 */
  border-radius: 20px;
}

input {
  background: rgba(0,0,0,0.3);
  border: 1px solid transparent;
  color: var(--text-main, #fff);
  padding: 8px 16px;
  width: 320px;
  border-radius: 20px;
  outline: none;
  transition: 0.3s;
}
input:focus {
  border-color: var(--primary);
  background: rgba(0,0,0,0.5);
}
input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* 當處於流式傳輸時，隱藏輸入框原本的文字，避免重疊 */
input.hidden-text {
  color: transparent; 
}

/* 狀態顯示層 (靜態文本) */
.status-overlay-text {
  position: absolute;
  left: 16px;
  right: 30px;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  pointer-events: none;
  color: var(--primary, #00ffc8);
  font-size: 12px;
  font-weight: bold;
  letter-spacing: 0.5px;
  text-shadow: 0 0 5px rgba(0, 255, 200, 0.5);
  animation: fadeIn 0.3s ease;
}

/* ===== SSE 流式傳輸特效層 ===== */
.stream-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.85); /* 深色背景遮擋 */
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  padding-left: 16px;
  z-index: 10;
  border-radius: 20px;
  pointer-events: none; /* 確保不可點擊 */
}

.glitch-container {
  display: flex;
  align-items: center;
  overflow: hidden;
  white-space: nowrap;
  max-width: 280px;
}

.glitch-text {
  font-family: "Courier New", monospace;
  font-size: 12px;
  font-weight: bold;
  color: var(--primary, #00ffc8);
  letter-spacing: 1px;
  position: relative;
  animation: text-flicker 2s infinite;
}

.cursor-blink {
  color: var(--accent, #fff);
  margin-left: 4px;
  font-weight: bold;
  animation: blink 1s step-end infinite;
}

/* 掃描線動畫 */
.scan-line {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 2px;
  background: var(--primary, #00ffc8);
  opacity: 0.6;
  box-shadow: 0 0 10px var(--primary, #00ffc8);
  animation: scan 2s linear infinite;
}

/* 呼吸燈 */
.emotion-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-left: 12px;
  /* 確保在 overlay 之上顯示，或者被 overlay 遮擋視需求而定，這裡設為 z-index 11 讓它浮在特效上 */
  z-index: 11; 
  animation: breathe infinite ease-in-out;
  transition: background-color 0.8s ease, box-shadow 0.8s ease, animation-duration 0.8s ease;
}

/* =========================================
   高級發送按鈕樣式 (核心修改部分)
   ========================================= */
.send-btn {
  position: relative;
  background: var(--primary, #00ffc8);
  color: #000;
  border: none;
  padding: 0; /* 移除 padding，改用寬高控制 */
  border-radius: 20px;
  font-weight: bold;
  font-size: 12px;
  cursor: pointer;
  
  /* 關鍵：使用 transition 實現形狀和顏色的平滑過渡 */
  transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  
  /* 初始狀態 (Idle) */
  width: 60px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.send-btn:hover:not(:disabled) {
  filter: brightness(1.1);
  transform: translateY(-1px);
  box-shadow: 0 0 15px var(--primary-dim);
}

.send-btn:disabled {
  cursor: not-allowed;
}

/* 狀態 2: Loading (變形為圓形，背景變透明以顯示光環) */
.send-btn.is-loading {
  width: 34px; /* 收縮為正圓 */
  height: 34px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.3); /* 深色背景 */
  border: 1px solid var(--primary-dim);
  box-shadow: 0 0 10px var(--primary-dim);
}

/* 狀態 3: Streaming (保持圓形，低調處理) */
.send-btn.is-streaming {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  opacity: 0.8;
}

/* ===== 科技感加載動畫 (Tech Loader) ===== */
.tech-loader {
  position: relative;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 軌道環 */
.orbit-ring {
  position: absolute;
  border-radius: 50%;
  border: 2px solid transparent;
}

/* 外環：主色，順時針快轉 */
.orbit-ring.outer {
  width: 100%;
  height: 100%;
  border-top-color: var(--primary);
  border-right-color: var(--primary);
  opacity: 0.9;
  animation: tech-spin 0.8s linear infinite;
  box-shadow: 0 0 5px var(--primary-dim);
}

/* 內環：強調色，逆時針慢轉 */
.orbit-ring.inner {
  width: 60%;
  height: 60%;
  border-bottom-color: var(--accent);
  border-left-color: var(--accent);
  opacity: 0.7;
  animation: tech-spin 1.2s linear infinite reverse;
}

/* 核心點：呼吸 */
.core-dot {
  width: 4px;
  height: 4px;
  background: var(--primary);
  border-radius: 50%;
  box-shadow: 0 0 6px var(--primary);
  animation: core-pulse 1s ease-in-out infinite;
}

/* ===== 傳輸中動畫 (Streaming Icon) ===== */
.streaming-icon {
  display: flex;
  gap: 2px;
  align-items: center;
  justify-content: center;
  height: 12px;
}

.wave-bar {
  width: 3px;
  background: var(--primary);
  border-radius: 2px;
  animation: wave 1s ease-in-out infinite;
}
.wave-bar:nth-child(1) { height: 6px; animation-delay: 0s; }
.wave-bar:nth-child(2) { height: 10px; animation-delay: 0.1s; }
.wave-bar:nth-child(3) { height: 6px; animation-delay: 0.2s; }

/* 動畫關鍵幀 */
@keyframes tech-spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

@keyframes core-pulse {
  0%, 100% { opacity: 0.5; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}

@keyframes wave {
  0%, 100% { height: 4px; opacity: 0.5; }
  50% { height: 12px; opacity: 1; }
}

@keyframes scan {
  0% { top: 0%; opacity: 0; }
  10% { opacity: 1; }
  90% { opacity: 1; }
  100% { top: 100%; opacity: 0; }
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

@keyframes text-flicker {
  0% { opacity: 0.1; }
  2% { opacity: 1; }
  8% { opacity: 0.1; }
  9% { opacity: 1; }
  12% { opacity: 0.1; }
  20% { opacity: 1; }
  25% { opacity: 1; }
  30% { opacity: 0.3; }
  70% { opacity: 0.9; text-shadow: 2px 0 var(--accent, #fff); }
  72% { opacity: 0.2; }
  77% { opacity: 0.9; }
  100% { opacity: 0.9; }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes breathe {
  0%, 100% { opacity: 0.3; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}

/* Vue Transition */
.fade-overlay-enter-active,
.fade-overlay-leave-active {
  transition: opacity 0.3s ease;
}
.fade-overlay-enter-from,
.fade-overlay-leave-to {
  opacity: 0;
}
</style>
