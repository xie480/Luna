<template>
  <div class="chat-bar-wrapper" @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')">
    
    <!-- 設置按鈕 -->
    <button class="icon-btn settings-btn" @click="$emit('open-settings')">
      ⚙️
    </button>

    <!-- 歷史記錄按鈕 -->
    <button class="icon-btn history-btn" @click="$emit('toggle-history')">
      📅
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
  // 憤怒/煩躁 (紅/快)
  Angry: { color: '#ff2a2a', speed: '0.8s', intensity: '0 0 15px' },
  Annoyed: { color: '#ff5500', speed: '1.2s', intensity: '0 0 10px' },
  Irritated: { color: '#ff5500', speed: '1.0s', intensity: '0 0 10px' },
  Tsundere: { color: '#ff0055', speed: '1.5s', intensity: '0 0 12px' },
  
  // 悲傷/消極 (藍/慢)
  Sad: { color: '#4a90e2', speed: '3s', intensity: '0 0 8px' },
  Lonely: { color: '#5065a5', speed: '3.5s', intensity: '0 0 6px' },
  Despair: { color: '#2c3e50', speed: '4s', intensity: '0 0 5px' },
  Broken: { color: '#000000', speed: '5s', intensity: '0 0 2px' },
  
  // 快樂/積極 (橙/黃/粉)
  Smile: { color: '#ffd700', speed: '2s', intensity: '0 0 12px' },
  Happy: { color: '#ffaa00', speed: '1.5s', intensity: '0 0 14px' },
  Affectionate: { color: '#ff69b4', speed: '2s', intensity: '0 0 15px' },
  Hopeful: { color: '#00ffc8', speed: '2.5s', intensity: '0 0 12px' },
  
  // 恐懼/焦慮 (紫/顫抖)
  Fearful: { color: '#8e44ad', speed: '0.5s', intensity: '0 0 8px' },
  Anxious: { color: '#9b59b6', speed: '0.6s', intensity: '0 0 8px' },
  Uneasy: { color: '#a569bd', speed: '1s', intensity: '0 0 8px' },
  
  // 默認
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
  gap: 10px;
  background: var(--bg-panel, rgba(5,10,19,0.9));
  padding: 10px 15px;
  border-radius: 50px;
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  backdrop-filter: blur(8px);
  box-shadow: 0 10px 30px rgba(0,0,0,0.5);
  z-index: 9000;
}

.icon-btn {
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  opacity: 0.7;
  transition: 0.2s;
  padding: 5px;
}
.icon-btn:hover { opacity: 1; transform: scale(1.1); }

.input-container {
  position: relative;
  display: flex;
  align-items: center;
}

input {
  background: rgba(0,0,0,0.3);
  border: none;
  color: var(--text-main, #fff);
  padding: 8px 12px;
  width: 300px;
  border-radius: 4px;
  outline: none;
}

/* 呼吸燈 */
.emotion-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-left: 10px;
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
  padding: 6px 16px;
  border-radius: 20px;
  font-weight: bold;
  font-size: 12px;
  cursor: pointer;
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
