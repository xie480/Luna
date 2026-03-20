<template>
  <div class="approval-mask" @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')">
    <div class="approval-modal">
      <div class="modal-header">
        <span class="warning-icon">⚠️</span>
        <h3>敏感操作請求</h3>
      </div>
      
      <div class="modal-body">
        <p class="desc">AI 正在請求執行敏感操作，請確認參數。</p>
        
        <div class="info-row">
          <span class="label">技能名稱:</span>
          <span class="value highlight">{{ task?.skillName }}</span>
        </div>
        
        <div class="args-container">
          <div class="label">參數詳情:</div>
          <pre class="args-json">{{ formattedArgs }}</pre>
        </div>
        
        <div class="countdown">
          任務有效期: <span :class="{ 'text-danger': timeLeft < 60 }">{{ formatTime(timeLeft) }}</span>
        </div>
      </div>
      
      <div class="modal-footer">
        <button class="btn-reject" @click="$emit('reject')">拒絕 (Reject)</button>
        <button class="btn-approve" @click="$emit('approve')">同意 (Approve)</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';

const props = defineProps({
  task: {
    type: Object,
    default: () => ({})
  }
});

const emit = defineEmits(['approve', 'reject', 'mouseenter', 'mouseleave']);

// 格式化 JSON 參數以便閱讀
const formattedArgs = computed(() => {
  try {
    return JSON.stringify(JSON.parse(props.task?.argsJson || '{}'), null, 2);
  } catch (e) {
    return props.task?.argsJson || '{}';
  }
});

// 倒計時邏輯 (後端 Redis TTL 為 10 分鐘)
const timeLeft = ref(600);
let timer = null;

onMounted(() => {
  if (props.task?.createTime) {
    const elapsed = Math.floor((Date.now() - props.task.createTime) / 1000);
    timeLeft.value = Math.max(0, 600 - elapsed);
  }
  
  timer = setInterval(() => {
    if (timeLeft.value > 0) {
      timeLeft.value--;
    } else {
      clearInterval(timer);
      emit('reject'); // 超時自動拒絕
    }
  }, 1000);
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

function formatTime(seconds) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}
</script>

<style scoped>
.approval-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  pointer-events: auto;
}

.approval-modal {
  width: 480px;
  background: var(--bg-panel, rgba(15, 20, 25, 0.98));
  border: 1px solid #ff4d4f;
  border-radius: 8px;
  box-shadow: 0 15px 40px rgba(255, 77, 79, 0.25), 0 0 10px rgba(255, 77, 79, 0.1) inset;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: #eee;
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
  animation: modal-pop 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes modal-pop {
  from { opacity: 0; transform: scale(0.9) translateY(10px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.modal-header {
  background: linear-gradient(90deg, rgba(255, 77, 79, 0.15), rgba(255, 77, 79, 0.05));
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 77, 79, 0.3);
  display: flex;
  align-items: center;
  gap: 10px;
}

.warning-icon {
  font-size: 18px;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.modal-header h3 {
  margin: 0;
  color: #ff7875;
  font-size: 15px;
  letter-spacing: 1px;
  font-weight: bold;
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.desc {
  font-size: 13px;
  color: #ccc;
  margin: 0;
  line-height: 1.5;
}

.info-row {
  display: flex;
  gap: 10px;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.03);
  padding: 10px;
  border-radius: 4px;
  border-left: 3px solid var(--primary, #00ffc8);
}

.label {
  color: #888;
  min-width: 70px;
}

.highlight {
  color: var(--primary, #00ffc8);
  font-weight: bold;
  word-break: break-all;
}

.args-container {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.args-json {
  background: rgba(0, 0, 0, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 12px;
  border-radius: 6px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #a5d6a7;
  max-height: 220px;
  overflow-y: auto;
  margin: 0;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.args-json::-webkit-scrollbar { width: 6px; }
.args-json::-webkit-scrollbar-track { background: transparent; }
.args-json::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 3px; }

.countdown {
  font-size: 12px;
  text-align: right;
  color: #888;
  margin-top: 4px;
}

.text-danger {
  color: #ff4d4f;
  font-weight: bold;
  font-family: monospace;
  font-size: 14px;
}

.modal-footer {
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  background: rgba(0, 0, 0, 0.3);
}

button {
  padding: 8px 24px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
  outline: none;
}

.btn-reject {
  background: rgba(255, 77, 79, 0.1);
  color: #ff7875;
  border: 1px solid rgba(255, 77, 79, 0.5);
}

.btn-reject:hover {
  background: rgba(255, 77, 79, 0.2);
  box-shadow: 0 0 10px rgba(255, 77, 79, 0.2);
}

.btn-approve {
  background: var(--primary, #00ffc8);
  color: #000;
  box-shadow: 0 0 10px rgba(0, 255, 200, 0.2);
}

.btn-approve:hover {
  filter: brightness(1.15);
  box-shadow: 0 0 15px var(--primary, #00ffc8);
  transform: translateY(-1px);
}
</style>
