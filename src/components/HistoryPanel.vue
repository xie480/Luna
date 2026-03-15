<template>
  <div 
    class="history-panel" 
    :style="{ left: x + 'px', top: y + 'px' }"
    @mouseenter="$emit('mouseenter')" 
    @mouseleave="$emit('mouseleave')"
  >
    <!-- 標題欄 (可拖拽) -->
    <div class="history-header" @mousedown="startDrag">
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

      <!-- 右側：聊天記錄 (Discord 風格 - 無頭像) -->
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

            <!-- 對話消息 (Discord 風格) -->
            <div v-else class="discord-msg-group">
              <div class="discord-header">
                <span class="username">{{ msg.sender === 'luna' ? 'LUNA' : 'USER' }}</span>
                <span class="timestamp">{{ msg.time }}</span>
              </div>
              <div class="discord-content">
                {{ msg.content }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue';

const props = defineProps(['visible']);
const emit = defineEmits(['close', 'mouseenter', 'mouseleave']);

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

// 格式化為 YYYY:MM:DD
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
  // 格式化為 YYYY:MM
  const ym = `${currentYear.value}:${String(currentMonth.value + 1).padStart(2, '0')}`;
  try {
    const res = await window.desktopApi.historyDate(ym);
    availableDates.value = new Set(res || []);
  } catch (e) {
    console.error("Fetch dates error", e);
  }
}

async function selectDate(dateStr) {
  // 優化：如果該日期沒有數據，則不執行任何操作
  if (!availableDates.value.has(dateStr)) return;

  selectedDate.value = dateStr;
  loading.value = true;
  messages.value = [];
  try {
    // dateStr 已經是 YYYY:MM:DD 格式
    const res = await window.desktopApi.history(dateStr);
    // res 格式: ["TAG:Content:Time", ...]
    
    messages.value = (res || []).reduce((acc, line) => {
      // 解析正則: TAG:Content:Time
      const match = line.match(/^([A-Z]+):(.*):(\d{1,2}:\d{2}:\d{2})$/);
      
      if (match) {
        const [_, tag, content, time] = match;
        
        // 過濾 SUMMARY
        if (tag !== 'SUMMARY') {
          let sender = 'system';
          if (tag === 'LUNA') sender = 'luna';
          else if (tag === 'USER') sender = 'user';
          else if (tag === 'STARTUP') sender = 'system';
          
          acc.push({
            sender,
            content,
            time // 直接使用字符串時間
          });
        }
      }
      return acc;
    }, []);

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
  // 嘗試加載當天，如果當天沒數據，selectDate 內部的判斷會攔截，但這裡我們希望初始化時能顯示
  // 為了體驗，可以不強制攔截初始化，或者在 fetchAvailableDates 後自動選擇最近的一天
  // 這裡暫時保持默認選擇當天，如果當天沒數據則顯示空白
  const today = formatDateStr(new Date());
  // 這裡手動調用一次 API 獲取當天數據，繞過 selectDate 的 has check，確保剛打開時能看到當天（如果有）
  // 或者等待 availableDates 加載完。為了簡單，這裡直接調用：
  window.desktopApi.history(today).then(res => {
    if (res && res.length > 0) {
      availableDates.value.add(today); // 確保當天被標記為有數據
      selectDate(today);
    }
  });
});

// 獲取當前時間字符串 HH:MM:SS
function getCurrentTimeStr() {
  const d = new Date();
  return d.toLocaleTimeString('en-GB', { hour12: false });
}

// 暴露給父組件調用，用於實時插入新消息
defineExpose({
  pushMessage: (msg) => {
    if (selectedDate.value === formatDateStr(new Date())) {
      // 確保消息有 time 字段
      const msgWithTime = {
        ...msg,
        time: msg.time || getCurrentTimeStr()
      };
      messages.value.push(msgWithTime);
      nextTick(scrollToBottom);
    }
  }
});
</script>

<style scoped>
.history-panel {
  position: fixed;
  width: 800px; /* 稍微加寬以適應 Discord 風格 */
  height: 550px;
  background: var(--bg-panel, rgba(20, 22, 26, 0.98)); /* 更深沈的背景 */
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.9);
  z-index: 9999;
  backdrop-filter: blur(12px);
  color: #dcddde; /* Discord 默認文字顏色 */
  font-family: "gg sans", "Segoe UI", "Helvetica Neue", Helvetica, Arial, sans-serif;
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

/* ===== 左側日曆 ===== */
.calendar-section {
  width: 240px;
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
  
  /* 默認狀態：無數據，不可點擊，變暗 */
  opacity: 0.2;
  color: #fff;
  pointer-events: none;
  background: transparent;
}

/* 有數據的日期：高亮，可點擊 */
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

/* 選中狀態 */
.cal-day.selected {
  background: var(--primary, #00ffc8) !important;
  color: #000 !important;
  font-weight: bold;
  box-shadow: 0 0 10px rgba(0, 255, 200, 0.3);
}

/* ===== 右側聊天記錄 (Discord Style) ===== */
.chat-section {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  background: transparent;
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
  padding: 20px 0; /* 上下 padding，左右由 message group 控制 */
  display: flex;
  flex-direction: column;
  gap: 4px; /* 消息間距更緊湊 */
}

/* 滾動條美化 */
.chat-list::-webkit-scrollbar { width: 8px; }
.chat-list::-webkit-scrollbar-track { background: #2e3338; }
.chat-list::-webkit-scrollbar-thumb { background: #202225; border-radius: 4px; }

.chat-row {
  width: 100%;
  padding: 2px 16px;
  transition: background 0.1s;
}
.chat-row:hover {
  background: rgba(255,255,255,0.02); /* 鼠標懸停整行高亮 */
}

/* 系統消息 */
.system-msg {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0;
  padding: 0 16px;
}
.sys-line { flex: 1; height: 1px; background: #4f545c; }
.sys-text { 
  font-size: 12px; 
  color: #72767d; 
  font-weight: 600;
}

/* Discord 消息組 */
.discord-msg-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 10px; /* 消息組之間的間距 */
}

.discord-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.username {
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
}
.username:hover { text-decoration: underline; }

/* LUNA 樣式 */
.luna .username {
  color: var(--primary, #00ffc8);
  text-shadow: 0 0 10px rgba(0, 255, 200, 0.2);
}

/* USER 樣式 */
.user .username {
  color: #ffffff;
}

.timestamp {
  font-size: 11px;
  color: #72767d;
  font-weight: 400;
}

.discord-content {
  font-size: 14px;
  line-height: 1.375rem;
  color: #dcddde;
  white-space: pre-wrap;
  word-wrap: break-word;
  padding-right: 10px;
}

/* LUNA 的消息內容稍微亮一點 */
.luna .discord-content {
  color: #e8fff8;
}
</style>
