<template>
  <div class="approval-mask" @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')">
    <div
      class="approval-modal"
      :style="{ left: modalX + 'px', top: modalY + 'px' }"
    >
      <div class="modal-header" @mousedown="startDrag">
        <span class="warning-icon">⚠️</span>
        <h3>敏感操作请求</h3>
      </div>

      <div class="modal-body">
        <p class="desc">AI 正在请求执行敏感操作，请确认参数。</p>

        <div class="info-row">
          <span class="label">操作名称:</span>
          <span class="value highlight">{{ task?.skillName || "-" }}</span>
        </div>

        <div class="args-container">
          <div class="label">参数详情:</div>

          <div v-if="kvRows.length" class="kv-list">
            <div class="kv-row" v-for="row in kvRows" :key="row.key">
              <span class="kv-key">{{ row.key }}</span>
              <span class="kv-sep">:</span>
              <span class="kv-val">{{ row.value }}</span>
            </div>
          </div>
          <div v-else class="empty-kv">（参数不是对象或为空，已显示原始 JSON）</div>

          <pre class="args-json">{{ formattedArgs }}</pre>
        </div>

        <div class="countdown">
          任务有效期:
          <span :class="{ 'text-danger': timeLeft < 60 }">{{ formatTime(timeLeft) }}</span>
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-reject" @click="$emit('reject')">拒绝 (Reject)</button>
        <button class="btn-approve" @click="$emit('approve')">同意 (Approve)</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";

const props = defineProps({
  task: {
    type: Object,
    default: () => ({}),
  },
});

const emit = defineEmits(["approve", "reject", "mouseenter", "mouseleave"]);

function safeParseArgs() {
  try {
    const raw = props.task?.argsJson;
    if (!raw) return {};
    if (typeof raw === "object") return raw;
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

const parsedArgs = computed(() => safeParseArgs());

const kvRows = computed(() => {
  const data = parsedArgs.value;
  if (!data || typeof data !== "object" || Array.isArray(data)) return [];

  return Object.entries(data).map(([key, value]) => {
    let v = value;
    if (typeof value === "object") {
      try {
        v = JSON.stringify(value);
      } catch {
        v = String(value);
      }
    }
    return { key, value: String(v) };
  });
});

const formattedArgs = computed(() => {
  try {
    const raw = props.task?.argsJson;
    if (!raw) return "{}";
    if (typeof raw === "object") return JSON.stringify(raw, null, 2);
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return props.task?.argsJson || "{}";
  }
});

// 倒计时（默认 10 分钟）
const TTL_SECONDS = 600;
const timeLeft = ref(TTL_SECONDS);
let timer = null;

function resetTimer() {
  if (timer) clearInterval(timer);

  let initial = TTL_SECONDS;
  if (props.task?.createTime) {
    const elapsed = Math.floor((Date.now() - props.task.createTime) / 1000);
    initial = Math.max(0, TTL_SECONDS - elapsed);
  }
  timeLeft.value = initial;

  timer = setInterval(() => {
    if (timeLeft.value > 0) {
      timeLeft.value--;
    } else {
      clearInterval(timer);
      timer = null;
      emit("reject"); // 超时自动拒绝
    }
  }, 1000);
}

watch(
  () => props.task?.taskId,
  () => {
    resetTimer();
    centerModal();
  },
  { immediate: true }
);

// 拖拽逻辑
const modalX = ref(0);
const modalY = ref(0);
let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function centerModal() {
  const w = 440;
  const h = 460;
  modalX.value = Math.max(12, Math.floor((window.innerWidth - w) / 2));
  modalY.value = Math.max(12, Math.floor((window.innerHeight - h) / 2));
}

function startDrag(e) {
  isDragging = true;
  dragOffset.x = e.clientX - modalX.value;
  dragOffset.y = e.clientY - modalY.value;
  window.addEventListener("mousemove", onDrag);
  window.addEventListener("mouseup", stopDrag);
}

function onDrag(e) {
  if (!isDragging) return;
  const maxX = window.innerWidth - 120;
  const maxY = window.innerHeight - 80;
  modalX.value = Math.min(Math.max(e.clientX - dragOffset.x, -320), maxX);
  modalY.value = Math.min(Math.max(e.clientY - dragOffset.y, -380), maxY);
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener("mousemove", onDrag);
  window.removeEventListener("mouseup", stopDrag);
}

onMounted(() => {
  if (!timer) resetTimer();
  centerModal();
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
  stopDrag();
});

function formatTime(seconds) {
  const m = Math.floor(seconds / 60)
    .toString()
    .padStart(2, "0");
  const s = (seconds % 60).toString().padStart(2, "0");
  return `${m}:${s}`;
}
</script>

<style scoped>
.approval-mask {
  position: fixed;
  inset: 0;
  background: transparent;
  display: block;
  z-index: 10000;
  pointer-events: none;
}

.approval-modal {
  position: fixed;
  width: 440px;
  max-width: 88vw;
  max-height: 86vh;
  background: var(--bg-panel, rgba(15, 20, 25, 0.98));
  border: 1px solid #ff4d4f;
  border-radius: 8px;
  box-shadow: 0 12px 30px rgba(255, 77, 79, 0.18), 0 0 8px rgba(255, 77, 79, 0.08) inset;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: #eee;
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
  animation: modal-pop 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
  pointer-events: auto;
}

@keyframes modal-pop {
  from { opacity: 0; transform: scale(0.94) translateY(8px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.modal-header {
  background: linear-gradient(90deg, rgba(255, 77, 79, 0.15), rgba(255, 77, 79, 0.05));
  padding: 12px 16px;
  border-bottom: 1px solid rgba(255, 77, 79, 0.3);
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: move;
  user-select: none;
}

.warning-icon {
  font-size: 16px;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.modal-header h3 {
  margin: 0;
  color: #ff7875;
  font-size: 14px;
  letter-spacing: 0.5px;
  font-weight: bold;
}

.modal-body {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
}

.desc {
  font-size: 12px;
  color: #ccc;
  margin: 0;
  line-height: 1.45;
}

.info-row {
  display: flex;
  gap: 8px;
  font-size: 13px;
  background: rgba(255, 255, 255, 0.03);
  padding: 8px;
  border-radius: 4px;
  border-left: 3px solid var(--primary, #00ffc8);
}

.label {
  color: #888;
  min-width: 72px;
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

.kv-list {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  padding: 8px 10px;
  max-height: 140px;
  overflow-y: auto;
}

.kv-row {
  display: grid;
  grid-template-columns: minmax(70px, 140px) 10px 1fr;
  gap: 5px;
  font-size: 12px;
  line-height: 1.45;
  padding: 2px 0;
}

.kv-key {
  color: #81d4fa;
  word-break: break-all;
}

.kv-sep {
  color: #888;
  text-align: center;
}

.kv-val {
  color: #e8fff8;
  word-break: break-word;
}

.empty-kv {
  font-size: 12px;
  color: #999;
}

.args-json {
  background: rgba(0, 0, 0, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 10px;
  border-radius: 6px;
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  color: #a5d6a7;
  max-height: 140px;
  overflow-y: auto;
  margin: 0;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.args-json::-webkit-scrollbar,
.kv-list::-webkit-scrollbar {
  width: 6px;
}
.args-json::-webkit-scrollbar-track,
.kv-list::-webkit-scrollbar-track {
  background: transparent;
}
.args-json::-webkit-scrollbar-thumb,
.kv-list::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.2);
  border-radius: 3px;
}

.countdown {
  font-size: 12px;
  text-align: right;
  color: #888;
  margin-top: 2px;
}

.text-danger {
  color: #ff4d4f;
  font-weight: bold;
  font-family: monospace;
  font-size: 13px;
}

.modal-footer {
  padding: 12px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  background: rgba(0, 0, 0, 0.3);
}

button {
  padding: 7px 16px;
  border-radius: 4px;
  font-size: 12px;
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
  box-shadow: 0 0 8px rgba(255, 77, 79, 0.2);
}

.btn-approve {
  background: var(--primary, #00ffc8);
  color: #000;
  box-shadow: 0 0 8px rgba(0, 255, 200, 0.2);
}

.btn-approve:hover {
  filter: brightness(1.15);
  box-shadow: 0 0 12px var(--primary, #00ffc8);
  transform: translateY(-1px);
}
</style>
