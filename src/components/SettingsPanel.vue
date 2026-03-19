<template>
  <div 
    class="settings-panel" 
    :style="{ left: x + 'px', top: y + 'px', width: width + 'px', height: height + 'px' }"
    @mouseenter="$emit('mouseenter')" 
    @mouseleave="$emit('mouseleave')"
  >
    <!-- 標題欄 (可拖拽) -->
    <div class="panel-header" @mousedown="startDrag">
      <span>SYSTEM SETTINGS</span>
      <button class="close-btn" @click="$emit('close')">×</button>
    </div>

    <div class="panel-body">
      <!-- 左側菜單 -->
      <div class="sidebar">
        <div 
          class="menu-item" 
          :class="{ active: activeTab === 'general' }"
          @click="activeTab = 'general'"
        >
          通用設置
        </div>
        <div 
          class="menu-item" 
          :class="{ active: activeTab === 'appearance' }"
          @click="activeTab = 'appearance'"
        >
          外貌定製
        </div>
        <div 
          class="menu-item" 
          :class="{ active: activeTab === 'system' }"
          @click="activeTab = 'system'"
        >
          系統狀態
        </div>
        <div 
          class="menu-item" 
          :class="{ active: activeTab === 'tools' }"
          @click="activeTab = 'tools'"
        >
          Tool管理
        </div>
      </div>

      <!-- 右側內容 -->
      <div class="content">
        
        <!-- 通用設置 -->
        <div v-if="activeTab === 'general'" class="tab-content">
          <div class="section">
            <div class="section-title">THEME / 主題</div>
            <div class="theme-control">
              <select v-model="selectedTheme">
                <option v-for="(t, key) in themes" :key="key" :value="key">{{ t.name }}</option>
              </select>
              <button class="apply-btn" @click="applyThemeSetting">應用</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">MODEL DISPLAY / 模型顯示</div>
            <label class="app-item">
              <input 
                type="checkbox" 
                :checked="isModelVisible"
                @change="$emit('toggle-model')"
              />
              <span>顯示 Live2D 模型</span>
            </label>
          </div>

          <div class="section">
            <div class="section-title">INITIAL SETUP / 初始設定</div>
            <p class="desc">調整模型在屏幕上的初始位置和大小。</p>
            <div class="btn-group">
              <button 
                class="action-btn" 
                :class="{ 'active-red': isSetupMode }"
                @click="toggleSetupMode"
              >
                {{ isSetupMode ? '設定完成' : '開始設定' }}
              </button>
              <button class="action-btn secondary" @click="$emit('reset-setup')">
                重置位置
              </button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">TRACKING / 視線追蹤</div>
            <p class="desc">點擊模型表面設置視線追蹤的參考中心點。</p>
            <div class="btn-group">
              <button 
                class="action-btn" 
                :class="{ 'active-red': isTrackingSetupMode }"
                @click="toggleTrackingSetup"
              >
                {{ isTrackingSetupMode ? '設定完成' : '開始設定' }}
              </button>
              <button class="action-btn secondary" @click="$emit('reset-tracking-setup')">
                重置中心
              </button>
            </div>
          </div>

          <!-- 退出按鈕移至通用設置底部右下角 -->
          <div class="section" style="text-align: right; margin-top: 30px; border: none;">
            <button class="quit-btn" @click="quitApp">退出應用</button>
          </div>
        </div>

        <!-- 外貌定製 -->
        <div v-if="activeTab === 'appearance'" class="tab-content">
          <div class="section">
            <div class="section-title">ACCESSORIES / 配飾與表情</div>
            <div class="appearance-list">
              <label v-for="file in appearance.APPEARANCE_FILES" :key="file" class="app-item">
                <input 
                  type="checkbox" 
                  v-model="appearance.appearanceEnabled.value[file]"
                  @change="appearance.onAppearanceToggle(file, core)"
                />
                <span>{{ appearance.displayAppearanceName(file) }}</span>
              </label>
            </div>
            <button class="reset-btn" @click="$emit('reset-model')">重置所有外貌</button>
          </div>
        </div>

        <!-- 系統狀態 -->
        <div v-if="activeTab === 'system'" class="tab-content">
          <div class="section">
            <div class="section-title">USER STATUS / 用戶狀態</div>
            <div class="status-row">
              <div class="status-indicator" :class="{ active: isLoggedIn }"></div>
              <span class="status-text">{{ isLoggedIn ? '已登錄 (Online)' : '未連接 (Offline)' }}</span>
              <!-- 新增退出登錄按鈕 -->
              <button v-if="isLoggedIn" class="logout-btn" @click="$emit('logout')">退出登錄</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">WINDOW / 窗口設定</div>
            <label class="app-item">
              <input 
                type="checkbox" 
                v-model="isAlwaysOnTop"
                @change="toggleAlwaysOnTop"
              />
              <span>窗口置頂 (Always On Top)</span>
            </label>
          </div>

          <div class="section">
            <div class="section-title">RHYTHM / 律動</div>
            <label class="app-item">
              <input 
                type="checkbox" 
                :checked="rhythm.showSystemAudioListening.value"
                @change="rhythm.toggleSystemAudio(core, { value: true })"
              />
              <span>開啟系統音頻律動 (Beta)</span>
            </label>
          </div>
        </div>

        <!-- Tool管理 -->
        <div v-if="activeTab === 'tools'" class="tab-content" style="height: 100%;">
          <ToolManager @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')" />
        </div>

      </div>
    </div>

    <!-- Resize Handles -->
    <div class="resize-handle sw" @mousedown.stop="startResize($event, 'sw')"></div>
    <div class="resize-handle se" @mousedown.stop="startResize($event, 'se')"></div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useTheme } from '../composables/useTheme';
import ToolManager from '../views/settings/ToolManager.vue';

const props = defineProps([
  'core', 'model', 'appearance', 'rhythm', 
  'isLoggedIn', 'isSetupMode', 'isTrackingSetupMode',
  'isModelVisible'
]);
const emit = defineEmits([
  'close', 'reset-model', 'toggle-setup', 'toggle-tracking-setup', 
  'reset-setup', 'reset-tracking-setup',
  'mouseenter', 'mouseleave', 'toggle-model', 'logout'
]);

// 拖拽邏輯
const x = ref(window.innerWidth / 2 - 275);
const y = ref(window.innerHeight / 2 - 200);
let isDragging = false;
let dragOffset = { x: 0, y: 0 };

// 尺寸邏輯
const width = ref(550);
const height = ref(400);
const minWidth = 400;
const minHeight = 300;

function startDrag(e) {
  if (e.target.closest('.close-btn')) return;
  isDragging = true;
  dragOffset.x = e.clientX - x.value;
  dragOffset.y = e.clientY - y.value;
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}

function onDrag(e) {
  if (!isDragging) return;
  // [Fix] 增加邊界檢查，防止拖出屏幕
  const maxX = window.innerWidth - 50;
  const maxY = window.innerHeight - 50;
  x.value = Math.min(Math.max(e.clientX - dragOffset.x, -500), maxX);
  y.value = Math.min(Math.max(e.clientY - dragOffset.y, -350), maxY);
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}

// Resize logic
let resizeStart = { x: 0, y: 0 };
let initialSize = { w: 0, h: 0 };
let initialPos = { x: 0, y: 0 };
let resizeDir = '';

function startResize(e, dir) {
  e.preventDefault();
  resizeDir = dir;
  resizeStart = { x: e.clientX, y: e.clientY };
  initialSize = { w: width.value, h: height.value };
  initialPos = { x: x.value, y: y.value };
  
  window.addEventListener('mousemove', onResize);
  window.addEventListener('mouseup', stopResize);
}

function onResize(e) {
  const dx = e.clientX - resizeStart.x;
  const dy = e.clientY - resizeStart.y;
  
  if (resizeDir === 'se') {
    width.value = Math.max(minWidth, initialSize.w + dx);
    height.value = Math.max(minHeight, initialSize.h + dy);
  } else if (resizeDir === 'sw') {
    const newWidth = Math.max(minWidth, initialSize.w - dx);
    width.value = newWidth;
    height.value = Math.max(minHeight, initialSize.h + dy);
    // Adjust x to keep right edge stable
    x.value = initialPos.x + (initialSize.w - newWidth);
  }
}

function stopResize() {
  window.removeEventListener('mousemove', onResize);
  window.removeEventListener('mouseup', stopResize);
}

// 標籤頁邏輯
const activeTab = ref('general');

// 主題邏輯
const { THEMES, currentTheme, applyTheme } = useTheme();
const selectedTheme = ref(currentTheme.value);
const themes = THEMES;

function applyThemeSetting() {
  applyTheme(selectedTheme.value);
}

// 模式切換
function toggleSetupMode() {
  emit('toggle-setup');
}

function toggleTrackingSetup() {
  emit('toggle-tracking-setup');
}

function quitApp() {
  window.desktopApi?.quit?.();
}

// 窗口置頂邏輯
const isAlwaysOnTop = ref(true);

function toggleAlwaysOnTop() {
  window.desktopApi.setAlwaysOnTop(isAlwaysOnTop.value);
}
</script>

<style scoped>
.settings-panel {
  position: fixed;
  /* width & height are now inline styles */
  background: var(--bg-panel, rgba(5,10,19,0.95));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 8px;
  color: var(--text-main, #fff);
  display: flex;
  flex-direction: column;
  z-index: 9500;
  box-shadow: 0 20px 50px rgba(0,0,0,0.8);
  backdrop-filter: blur(10px);
  overflow: hidden;
}

.panel-header {
  padding: 12px 15px;
  background: rgba(0,0,0,0.2);
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: bold;
  letter-spacing: 1px;
  color: var(--primary, #00ffc8);
  cursor: move;
  user-select: none;
}
.close-btn { background: none; border: none; color: inherit; font-size: 20px; cursor: pointer; }

.panel-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* 左側菜單 */
.sidebar {
  width: 140px;
  background: var(--bg-sidebar, rgba(0,0,0,0.3));
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 10px 0;
}
.menu-item {
  padding: 12px 15px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-dim, #888);
  transition: 0.2s;
  border-left: 3px solid transparent;
}
.menu-item:hover {
  background: var(--hover, rgba(255,255,255,0.05));
  color: var(--text-main);
}
.menu-item.active {
  background: var(--hover, rgba(0,255,200,0.1));
  color: var(--primary);
  border-left-color: var(--primary);
}

/* 右側內容 */
.content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  position: relative;
}

.tab-content {
  animation: fadeIn 0.3s ease;
  height: 100%; /* Ensure full height for tool manager */
  display: flex;
  flex-direction: column;
}
@keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }

.section { margin-bottom: 25px; }
.section-title {
  font-size: 11px;
  color: var(--primary);
  opacity: 0.8;
  margin-bottom: 10px;
  letter-spacing: 1px;
  font-weight: bold;
  border-bottom: 1px solid var(--border);
  padding-bottom: 4px;
}
.desc { font-size: 12px; opacity: 0.6; margin-bottom: 10px; }

/* 用戶狀態燈 */
.status-row { display: flex; align-items: center; gap: 8px; }
.status-indicator {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: #555;
  box-shadow: 0 0 5px rgba(0,0,0,0.5);
  transition: all 0.3s ease;
}
.status-indicator.active {
  background: #00ff00;
  box-shadow: 0 0 8px #00ff00;
}
.status-text { font-size: 12px; }

/* 退出登錄按鈕 */
.logout-btn {
  margin-left: auto;
  background: rgba(255, 50, 50, 0.15);
  border: 1px solid rgba(255, 50, 50, 0.4);
  color: #ffaaaa;
  padding: 4px 10px;
  cursor: pointer;
  font-size: 11px;
  border-radius: 4px;
  transition: 0.2s;
}
.logout-btn:hover {
  background: rgba(255, 50, 50, 0.3);
}

/* 主題控制 */
.theme-control { display: flex; gap: 10px; }
select {
  flex: 1;
  background: rgba(0,0,0,0.5);
  border: 1px solid var(--border);
  color: var(--text-main);
  padding: 6px;
  outline: none;
  border-radius: 4px;
}
.apply-btn {
  background: var(--primary);
  color: #000;
  border: none;
  padding: 6px 15px;
  cursor: pointer;
  font-size: 12px;
  font-weight: bold;
  border-radius: 4px;
}

/* 按鈕組 */
.btn-group {
  display: flex;
  gap: 10px;
}

/* 綠色/紅色/灰色按鈕 */
.action-btn {
  flex: 1;
  padding: 8px;
  background: #28a745; /* 綠色 */
  color: white;
  border: none;
  cursor: pointer;
  transition: 0.2s;
  font-weight: bold;
  border-radius: 4px;
}
.action-btn.active-red {
  background: #dc3545; /* 紅色 */
}
.action-btn.secondary {
  background: #6c757d; /* 灰色 */
}
.action-btn.secondary:hover {
  background: #5a6268;
}

/* 外貌列表 */
.appearance-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
  margin-bottom: 10px;
  padding-right: 5px;
}
.app-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 0;
}
.app-item:hover { color: var(--primary); }
.reset-btn {
  background: rgba(255,255,255,0.1);
  border: 1px solid var(--border);
  color: var(--text-main);
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
  border-radius: 4px;
  width: 100%;
}

/* 退出按鈕 */
.quit-btn {
  background: rgba(255, 50, 50, 0.15);
  border: 1px solid rgba(255, 50, 50, 0.4);
  color: #ffaaaa;
  padding: 6px 15px;
  cursor: pointer;
  font-size: 12px;
  transition: 0.2s;
  border-radius: 4px;
}
.quit-btn:hover { background: rgba(255, 50, 50, 0.3); }

/* Resize Handles */
.resize-handle {
  position: absolute;
  bottom: 0;
  width: 15px;
  height: 15px;
  z-index: 10;
}
.resize-handle.sw {
  left: 0;
  cursor: sw-resize;
}
.resize-handle.se {
  right: 0;
  cursor: se-resize;
}
.resize-handle:hover {
  background: rgba(0, 255, 200, 0.2);
}

/* 全局滾動條美化 (針對 SettingsPanel 內部) */
.content::-webkit-scrollbar,
.appearance-list::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.content::-webkit-scrollbar-track,
.appearance-list::-webkit-scrollbar-track {
  background: transparent;
}
.content::-webkit-scrollbar-thumb,
.appearance-list::-webkit-scrollbar-thumb {
  background: var(--border); /* 使用主題變量 */
  border-radius: 3px;
}
.content::-webkit-scrollbar-thumb:hover,
.appearance-list::-webkit-scrollbar-thumb:hover {
  background: var(--primary); /* 使用主題變量 */
}
</style>
