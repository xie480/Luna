<template>
  <div
    class="history-panel"
    :class="{ fullscreen: isFullscreen }"
    :style="panelStyle"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="history-header" @mousedown="startDrag">
      <span class="title">MEMORY ARCHIVE</span>
      <div class="header-actions">
        <button class="header-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
          {{ isFullscreen ? "🗗" : "🗖" }}
        </button>
        <button class="close-btn" @click="$emit('close')">×</button>
      </div>
    </div>

    <div class="history-body">
      <div class="calendar-section">
        <div class="cal-nav">
          <button @click="changeMonth(-1)">&lt;</button>
          <span>{{ currentYear }} / {{ currentMonth + 1 }}</span>
          <button @click="changeMonth(1)">&gt;</button>
        </div>
        <div class="cal-grid">
          <div
            v-for="d in daysInMonth"
            :key="d.dateStr"
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

      <div class="chat-section">
        <div v-if="loading" class="loading-mask">LOADING...</div>
        <div class="chat-list" ref="chatListRef">
          <div v-for="(msg, idx) in messages" :key="idx" class="chat-row" :class="msg.sender">
            <div v-if="msg.sender === 'system'" class="system-msg">
              <span class="sys-line"></span>
              <span class="sys-text">{{ msg.content }}</span>
              <span class="sys-line"></span>
            </div>

            <div v-else class="line-msg-wrapper">
              <div class="line-bubble">{{ msg.content }}</div>
              <div class="line-time">{{ msg.time }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <template v-if="!isFullscreen">
      <div class="resize-handle sw" @mousedown.stop="startResize($event, 'sw')"></div>
      <div class="resize-handle se" @mousedown.stop="startResize($event, 'se')"></div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick, onBeforeUnmount } from 'vue';

const emit = defineEmits(['close', 'mouseenter', 'mouseleave']);

const x = ref(window.innerWidth / 2 - 350);
const y = ref(window.innerHeight / 2 - 250);
const width = ref(700);
const height = ref(550);
const minWidth = 620;
const minHeight = 420;

const isFullscreen = ref(false);
const prevRect = ref({ x: x.value, y: y.value, w: width.value, h: height.value });

const panelStyle = computed(() => {
  if (isFullscreen.value) return { left: '0px', top: '0px', width: '100vw', height: '100vh' };
  return { left: x.value + 'px', top: y.value + 'px', width: width.value + 'px', height: height.value + 'px' };
});

function saveRect() {
  prevRect.value = { x: x.value, y: y.value, w: width.value, h: height.value };
}
function toggleFullscreen() {
  if (!isFullscreen.value) {
    saveRect();
    isFullscreen.value = true;
  } else {
    isFullscreen.value = false;
    x.value = prevRect.value.x;
    y.value = prevRect.value.y;
    width.value = prevRect.value.w;
    height.value = prevRect.value.h;
  }
}

let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
  if (isFullscreen.value) return;
  if (
    e.target.closest('.close-btn') ||
    e.target.closest('.header-btn') ||
    e.target.closest('.chat-list') ||
    e.target.closest('.calendar-section')
  ) return;
  isDragging = true;
  dragOffset.x = e.clientX - x.value;
  dragOffset.y = e.clientY - y.value;
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}

function onDrag(e) {
  if (!isDragging) return;
  const maxX = window.innerWidth - 50;
  const maxY = window.innerHeight - 50;
  x.value = Math.min(Math.max(e.clientX - dragOffset.x, -650), maxX);
  y.value = Math.min(Math.max(e.clientY - dragOffset.y, -500), maxY);
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}

let resizeDir = '';
let resizeStart = { x: 0, y: 0 };
let initial = { x: 0, y: 0, w: 0, h: 0 };
function startResize(e, dir) {
  if (isFullscreen.value) return;
  resizeDir = dir;
  resizeStart = { x: e.clientX, y: e.clientY };
  initial = { x: x.value, y: y.value, w: width.value, h: height.value };
  window.addEventListener('mousemove', onResize);
  window.addEventListener('mouseup', stopResize);
}
function onResize(e) {
  const dx = e.clientX - resizeStart.x;
  const dy = e.clientY - resizeStart.y;
  if (resizeDir.includes('e')) width.value = Math.max(minWidth, initial.w + dx);
  if (resizeDir.includes('s')) height.value = Math.max(minHeight, initial.h + dy);
  if (resizeDir.includes('w')) {
    const nw = Math.max(minWidth, initial.w - dx);
    x.value = initial.x + (initial.w - nw);
    width.value = nw;
  }
}
function stopResize() {
  window.removeEventListener('mousemove', onResize);
  window.removeEventListener('mouseup', stopResize);
}

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
  return `${y}:${m}:${d}`;
}

function changeMonth(delta) {
  let m = currentMonth.value + delta;
  if (m > 11) { m = 0; currentYear.value++; }
  else if (m < 0) { m = 11; currentYear.value--; }
  currentMonth.value = m;
  fetchAvailableDates();
}

async function fetchAvailableDates() {
  const year = currentYear.value;
  const month = String(currentMonth.value + 1).padStart(2, '0');
  const ym = `${year}:${month}`;

  try {
    const res = await window.desktopApi.historyDate(ym);
    const formattedDates = (res || []).map(day => {
      const d = String(day).padStart(2, '0');
      return `${year}:${month}:${d}`;
    });

    availableDates.value = new Set(formattedDates);
  } catch (e) {
    console.error("Fetch dates error", e);
    availableDates.value = new Set();
  }
}

function parseHistory(res) {
  return (res || []).reduce((acc, rawLine) => {
    if (!rawLine) return acc;
    const line = typeof rawLine === 'string' ? rawLine : JSON.stringify(rawLine);
    const match = line.match(/^([A-Z_]+):([\s\S]*):(\d{1,2}:\d{2}:\d{2}(?:\.\d+)?)$/);

    if (match) {
      const [, tag, content, time] = match;
      let sender = 'system';
      if (tag === 'LUNA') sender = 'luna';
      else if (tag === 'USER') sender = 'user';
      acc.push({ sender, content: content.trim(), time });
    } else {
      const fallbackMatch = line.match(/^([A-Z_]+):([\s\S]*)$/);
      if (fallbackMatch) {
        const [, tag, content] = fallbackMatch;
        let sender = 'system';
        if (tag === 'LUNA') sender = 'luna';
        else if (tag === 'USER') sender = 'user';
        acc.push({ sender, content: content.trim(), time: '' });
      } else {
        acc.push({ sender: 'system', content: line, time: '' });
      }
    }
    return acc;
  }, []);
}

async function selectDate(dateStr) {
  if (!availableDates.value.has(dateStr)) return;
  selectedDate.value = dateStr;
  loading.value = true;
  messages.value = [];
  try {
    const res = await window.desktopApi.history(dateStr);
    messages.value = parseHistory(res);
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
  const today = formatDateStr(new Date());
  window.desktopApi.history(today).then(res => {
    if (res && res.length > 0) {
      availableDates.value.add(today);
      selectDate(today);
    }
  });
});

onBeforeUnmount(() => {
  stopDrag();
  stopResize();
  emit('mouseleave');
});

function getCurrentTimeStr() {
  const d = new Date();
  return d.toLocaleTimeString('en-GB', { hour12: false });
}

defineExpose({
  pushMessage: (msg) => {
    if (selectedDate.value === formatDateStr(new Date())) {
      const msgWithTime = {
        ...msg,
        time: msg.time || getCurrentTimeStr()
      };
      messages.value.push(msgWithTime);
      nextTick(scrollToBottom);
    }
  },
  refresh: async () => {
    const today = formatDateStr(new Date());
    const now = new Date();

    if (currentYear.value !== now.getFullYear() || currentMonth.value !== now.getMonth()) {
      currentYear.value = now.getFullYear();
      currentMonth.value = now.getMonth();
      await fetchAvailableDates();
    }

    availableDates.value.add(today);
    selectedDate.value = today;

    try {
      const res = await window.desktopApi.history(today);
      messages.value = parseHistory(res);
      await nextTick();
      scrollToBottom();
    } catch (e) {
      console.error("Fetch history error", e);
    }
  }
});
</script>

<style scoped>
.history-panel {
  position: fixed;
  background: var(--bg-panel, rgba(20, 22, 26, 0.98));
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.9);
  z-index: 9999;
  backdrop-filter: blur(12px);
  color: #dcddde;
  font-family: "Segoe UI", "Helvetica Neue", Helvetica, Arial, sans-serif;
  overflow: hidden;
}
.history-panel.fullscreen {
  border-radius: 0;
}
.history-header {
  padding: 14px 18px;
  background: rgba(0,0,0,0.3);
  border-bottom: 1px solid rgba(0,0,0,0.2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: move;
  user-select: none;
}
.title {
  font-size: 12px;
  letter-spacing: 1.5px;
  color: #72767d;
  font-weight: 700;
  text-transform: uppercase;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-btn {
  border: 1px solid rgba(255,255,255,0.2);
  background: rgba(255,255,255,0.08);
  color: var(--text-main, #fff);
  width: 24px;
  height: 22px;
  border-radius: 4px;
  cursor: pointer;
  line-height: 1;
}
.close-btn {
  background: none;
  border: none;
  color: #b9bbbe;
  font-size: 20px;
  cursor: pointer;
  transition: color 0.2s;
}
.close-btn:hover { color: #fff; }

.history-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.calendar-section {
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid rgba(255,255,255,0.06);
  padding: 20px;
  display: flex;
  flex-direction: column;
  background: rgba(0,0,0,0.2);
}
.cal-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  font-size: 14px;
  color: #fff;
  font-weight: bold;
}
.cal-nav button {
  background: none;
  border: none;
  color: #b9bbbe;
  cursor: pointer;
  padding: 4px 8px;
  font-size: 16px;
  transition: color 0.2s;
}
.cal-nav button:hover { color: #fff; }

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;
}
.cal-day {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  border-radius: 4px;
  transition: all 0.2s;
  opacity: 0.2;
  color: #fff;
  pointer-events: none;
  background: transparent;
}
.cal-day.has-data {
  opacity: 1;
  pointer-events: auto;
  cursor: pointer;
  background: rgba(255,255,255,0.05);
  color: #dcddde;
  font-weight: normal;
}
.cal-day.has-data:hover {
  background: rgba(255,255,255,0.1);
  color: #fff;
}
.cal-day.selected {
  background: var(--primary, #00ffc8) !important;
  color: #000 !important;
  font-weight: bold;
  box-shadow: 0 0 10px rgba(0, 255, 200, 0.3);
}

.chat-section {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  background: transparent;
  overflow: hidden;
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
  overflow-x: hidden;
  padding: 20px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.chat-list::-webkit-scrollbar { width: 6px; }
.chat-list::-webkit-scrollbar-track { background: transparent; }
.chat-list::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
.chat-list::-webkit-scrollbar-thumb:hover { background: var(--primary); }

.chat-row {
  display: flex;
  width: 100%;
}
.chat-row.luna { justify-content: flex-start; }
.chat-row.user { justify-content: flex-end; }
.chat-row.system { justify-content: center; }

.system-msg {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin: 8px 0;
}
.sys-line { flex: 1; height: 1px; background: rgba(255,255,255,0.1); }
.sys-text {
  font-size: 11px;
  color: rgba(255,255,255,0.4);
}

.line-msg-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  max-width: 85%;
}
.chat-row.user .line-msg-wrapper {
  flex-direction: row-reverse;
}
.line-bubble {
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;
  white-space: pre-wrap;
  box-shadow: 0 2px 5px rgba(0,0,0,0.2);
}
.luna .line-bubble {
  background: rgba(255, 255, 255, 0.1);
  color: #e8fff8;
  border-top-left-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.05);
}
.user .line-bubble {
  background: var(--primary, #00ffc8);
  color: #000;
  border-top-right-radius: 4px;
  font-weight: 500;
}
.line-time {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.4);
  margin-bottom: 2px;
  flex-shrink: 0;
}

.resize-handle {
  position: absolute;
  bottom: 0;
  width: 15px;
  height: 15px;
  z-index: 20;
}
.resize-handle.sw { left: 0; cursor: sw-resize; }
.resize-handle.se { right: 0; cursor: se-resize; }
</style>
