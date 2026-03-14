<template>
  <div class="chat-bar-wrapper" @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')">
    
    <!-- 設置按鈕 (SVG) -->
    <button class="icon-btn settings-btn" @click="$emit('open-settings')" title="設置">
      <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="3"></circle>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
      </svg>
    </button>

    <!-- 歷史記錄按鈕 (SVG) -->
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
      <input 
        ref="inputRef"
        v-model="inputText" 
        type="text" 
        placeholder="Type a message..." 
        @keydown.enter="sendMessage"
        :disabled="loading"
      />
      
      <!-- 呼吸燈情緒指示器 (無 tooltip) -->
      <div class="emotion-indicator" :style="emotionStyle"></div>
    </div>

    <button class="send-btn" @click="sendMessage" :disabled="loading || !inputText">
      {{ loading ? '...' : 'SEND' }}
    </button>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';

const props = defineProps(['loading', 'currentEmotion']);
const emit = defineEmits(['send', 'open-settings', 'toggle-history', 'mouseenter', 'mouseleave', 'close']);

const inputText = ref("");

function sendMessage() {
  if (!inputText.value.trim() || props.loading) return;
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
  default: { color: 'var(--primary, #00ffc8)', speed: '3s', intensity: '0 0 8px' }
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
  bottom: 40px;
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

/* 呼吸燈 */
.emotion-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-left: 12px;
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
}
.send-btn:hover:not(:disabled) {
  filter: brightness(1.1);
  transform: translateY(-1px);
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
