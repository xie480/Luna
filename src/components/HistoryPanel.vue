<template>
  <div 
    class="history-panel" 
    :style="{ left: x + 'px', top: y + 'px' }"
    @mousedown="startDrag"
  >
    <!-- 標題欄 -->
    <div class="history-header">
      <span class="title">MEMORY ARCHIVE</span>
      <button class="close-btn" @click="$emit('close')">×</button>
    </div>

    <div class="history-body">
      <!-- 左側：日曆 -->
      <div class="calendar-section">
        <div class="cal-nav">
          <button @click="changeMonth(-1)">&lt;</button>
          <span>{{ currentYear }} / {{ currentMonth + 1 }}</span>
          <button @click="changeMonth(1)">&gt;</button>
        </div>
        <div class="cal-grid">
          <div v-for="d in daysInMonth" :key="d.dateStr" 
               class="cal-day"
               :class="{ 
                 'has-data': availableDates.has(d.dateStr),
                 'selected': selectedDate === d.dateStr
               }"
               @click="selectDate(d.dateStr)"
          >
            {{ d.day }}
          </div>
        </div>
      </div>

      <!-- 右側：聊天記錄 (Discord 風格) -->
      <div class="chat-section">
        <div v-if="loading" class="loading-mask">LOADING...</div>
        <div class="chat-list" ref="chatListRef">
          <div v-for="(msg, idx) in messages" :key="idx" 
               class="chat-row" 
               :class="msg.sender"
          >
            <!-- 系統消息 -->
            <div v-if="msg.sender === 'system'" class="system-msg">
              <span class="sys-line"></span>
              <span class="sys-text">{{ msg.content }}</span>
              <span class="sys-line"></span>
            </div>

            <!-- 對話消息 -->
            <template v-else>
              <div class="avatar" v-if="msg.sender === 'luna'">🌙</div>
              <div class="msg-content-wrap">
                <div class="msg-meta">
                  <span class="name">{{ msg.sender === 'luna' ? 'LUNA' : 'USER' }}</span>
                  <span class="time">{{ formatTime(msg.timestamp) }}</span>
                </div>
                <div class="msg-bubble">{{ msg.content }}</div>
              </div>
              <div class="avatar user" v-if="msg.sender === 'user'">👤</div>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue';

const props = defineProps(['visible']);
const emit = defineEmits(['close']);

// 拖拽邏輯
const x = ref(window.innerWidth / 2 - 350);
const y = ref(window.innerHeight / 2 - 250);
let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
  if (e.target.closest('.close-btn') || e.target.closest('.chat-list') || e.target.closest('.calendar-section')) return;
  isDragging = true;
  dragOffset.x = e.clientX - x.value;
  dragOffset.y = e.clientY - y.value;
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}

function onDrag(e) {
  if (!isDragging) return;
  x.value = e.clientX - dragOffset.x;
  y.value = e.clientY - dragOffset.y;
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}

// 日曆邏輯
const now = new Date();
const currentYear = ref(now.getFullYear());
const currentMonth = ref(now.getMonth());
const selectedDate = ref(formatDateStr(now));
const availableDates = ref(new Set());
const messages = ref([]);
const loading = ref(false);
const chatListRef = ref(null);

const daysInMonth = computed(() => {
  const date = new Date(currentYear.value, currentMonth.value, 1);
  const days = [];
  while (date.getMonth() === currentMonth.value) {
    days.push({
      day: date.getDate(),
      dateStr: formatDateStr(date)
    });
    date.setDate(date.getDate() + 1);
  }
  return days;
});

function formatDateStr(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function formatTime(ts) {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function changeMonth(delta) {
  let m = currentMonth.value + delta;
  if (m > 11) { m = 0; currentYear.value++; }
  else if (m < 0) { m = 11; currentYear.value--; }
  currentMonth.value = m;
  fetchAvailableDates();
}

async function fetchAvailableDates() {
  const ym = `${currentYear.value}-${String(currentMonth.value + 1).padStart(2, '0')}`;
  try {
    const res = await window.desktopApi.historyDate(ym);
    availableDates.value = new Set(res || []);
  } catch (e) {
    console.error("Fetch dates error", e);
  }
}

async function selectDate(dateStr) {
  selectedDate.value = dateStr;
  loading.value = true;
  messages.value = [];
  try {
    const res = await window.desktopApi.history(dateStr);
    messages.value = (res || []).map(m => ({
      sender: m.role === 'assistant' ? 'luna' : (m.role === 'system' ? 'system' : 'user'),
      content: m.content,
      timestamp: m.created_at || Date.now()
    }));
    await nextTick();
    scrollToBottom();
  } catch (e) {
    console.error("Fetch history error", e);
  } finally {
    loading.value = false;
  }
}

function scrollToBottom() {
  if (chatListRef.value) {
    chatListRef.value.scrollTop = chatListRef.value.scrollHeight;
  }
}

onMounted(() => {
  fetchAvailableDates();
  selectDate(formatDateStr(new Date()));
});

// 暴露給父組件調用，用於實時插入新消息
defineExpose({
  pushMessage: (msg) => {
    if (selectedDate.value === formatDateStr(new Date())) {
      messages.value.push(msg);
      nextTick(scrollToBottom);
    }
  }
});
</script>

<style scoped>
.history-panel {
  position: fixed;
  width: 700px;
  height: 500px;
  background: var(--bg-panel, rgba(5,10,19,0.95));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 50px rgba(0,0,0,0.8);
  z-index: 9999;
  backdrop-filter: blur(10px);
  color: var(--text-main, #e8fff8);
  font-family: "Segoe UI", sans-serif;
}
.history-header {
  padding: 10px 15px;
  background: rgba(0,0,0,0.3);
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  cursor: move;
  user-select: none;
}
.title {
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--primary, #00ffc8);
}
.close-btn {
  background: none;
  border: none;
  color: var(--primary);
  font-size: 18px;
  cursor: pointer;
}
.history-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}
.calendar-section {
  width: 220px;
  border-right: 1px solid var(--border);
  padding: 10px;
  display: flex;
  flex-direction: column;
}
.cal-nav {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 14px;
}
.cal-nav button {
  background: none;
  border: 1px solid var(--border);
  color: var(--primary);
  cursor: pointer;
  padding: 2px 8px;
}
.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}
.cal-day {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  cursor: pointer;
  border-radius: 4px;
  color: rgba(255,255,255,0.5);
}
.cal-day:hover { background: rgba(255,255,255,0.1); }
.cal-day.has-data { 
  color: var(--text-main); 
  font-weight: bold;
  border: 1px solid var(--border);
}
.cal-day.selected {
  background: var(--primary);
  color: #000;
}

.chat-section {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  background: rgba(0,0,0,0.2);
}
.loading-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.5);
  z-index: 10;
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--primary);
}
.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.chat-row {
  display: flex;
  gap: 12px;
  max-width: 90%;
}
.chat-row.luna { align-self: flex-start; }
.chat-row.user { align-self: flex-end; flex-direction: row-reverse; }
.chat-row.system { align-self: center; width: 100%; justify-content: center; }

.avatar {
  width: 36px; height: 36px;
  background: rgba(255,255,255,0.1);
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
}
.msg-content-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.msg-meta {
  font-size: 10px;
  color: rgba(255,255,255,0.4);
  display: flex;
  gap: 8px;
}
.user .msg-meta { flex-direction: row-reverse; }

.msg-bubble {
  background: rgba(255,255,255,0.08);
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.user .msg-bubble {
  background: var(--primary-dim);
  border: 1px solid var(--border);
}

.system-msg {
  display: flex;
  align-items: center;
  gap: 10px;
  opacity: 0.5;
  font-size: 11px;
}
.sys-line { flex: 1; height: 1px; background: rgba(255,255,255,0.2); }
</style>
