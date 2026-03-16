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
      <div v-if="streaming" class="stream-overlay">
        <div class="glitch-container">
          <span class="glitch-text" :data-text="streamText || 'LUNA_CORE: PROCESSING...'">
            {{ streamText || 'LUNA_CORE: PROCESSING...' }}
          </span>
          <span class="cursor-blink">_</span>
        </div>
        <div class="scan-line"></div>
      </div>

      <!-- 呼吸燈情緒指示器 -->
      <div class="emotion-indicator" :style="emotionStyle"></div>
    </div>

    <!-- 發送按鈕 -->
    <button class="send-btn" @click="sendMessage" :disabled="loading || streaming || !inputText">
      <span v-if="loading" class="spinner"></span>
      <span v-else>SEND</span>
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

.status-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
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

@keyframes breathe {
  0%, 100% { opacity: 0.3; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}

.send-btn {
  background: var(--primary, #00ffc8);
  color: #000;
  border: none;
  padding: 8px 20px;
  border-radius: 20px;
  font-weight: bold;
  font-size: 12px;
  cursor: pointer;
  transition: 0.2s;
  min-width: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.send-btn:hover:not(:disabled) {
  filter: brightness(1.1);
  transform: translateY(-1px);
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 加載 Spinner */
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(0,0,0,0.3);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
