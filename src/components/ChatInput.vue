<template>
  <div class="chat-bar-wrapper">
    <!-- 设置按钮 -->
    <button class="btn-icon settings-btn" @click="$emit('open-settings')" title="设置">
      <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" stroke-width="2" fill="none">
        <circle cx="12" cy="12" r="3"></circle>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
      </svg>
    </button>

    <!-- 输入框 -->
    <input 
      ref="inputRef"
      v-model="text"
      class="chat-input"
      :placeholder="placeholder"
      @keyup.enter="sendMessage"
      @keydown.esc="$emit('close')"
    />

    <!-- 发送按钮 -->
    <button class="btn-icon send-btn" @click="sendMessage" :disabled="loading">
      <span v-if="loading">...</span>
      <span v-else>➤</span>
    </button>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue';

const props = defineProps({
  loading: Boolean,
  placeholder: { type: String, default: "和 Luna 说点什么..." }
});

const emit = defineEmits(['send', 'open-settings', 'close']);
const text = ref("");
const inputRef = ref(null);

onMounted(() => {
  nextTick(() => inputRef.value?.focus());
});

function sendMessage() {
  if (!text.value.trim() || props.loading) return;
  emit('send', text.value);
  text.value = "";
}
</script>

<style scoped>
.chat-bar-wrapper {
  position: fixed;
  bottom: 80px;
  left: 50%;
  transform: translateX(-50%);
  width: 460px;
  height: 54px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 27px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  padding: 0 12px;
  z-index: 9999;
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.4);
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.chat-bar-wrapper:hover {
  transform: translateX(-50%) scale(1.02);
}

.chat-input {
  flex: 1;
  border: none;
  background: transparent;
  height: 100%;
  font-size: 15px;
  padding: 0 12px;
  outline: none;
  color: #333;
}

.chat-input::placeholder {
  color: #999;
}

.btn-icon {
  background: none;
  border: none;
  cursor: pointer;
  padding: 8px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, color 0.2s;
  color: #666;
}

.btn-icon:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.08);
  color: #000;
}

.btn-icon:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.settings-btn {
  margin-right: 4px;
}
.send-btn {
  margin-left: 4px;
  font-size: 16px;
}
</style>
