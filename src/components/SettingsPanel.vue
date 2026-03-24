<template>
  <div
    class="settings-panel"
    :class="{ fullscreen: isFullscreen }"
    :style="panelStyle"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="panel-header" @mousedown="startDrag">
      <span>SYSTEM SETTINGS</span>
      <div class="header-actions">
        <button class="header-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
          {{ isFullscreen ? "🗗" : "🗖" }}
        </button>
        <button class="close-btn" @click="$emit('close')">×</button>
      </div>
    </div>

    <div class="panel-body">
      <div class="sidebar">
        <div class="menu-item" :class="{ active: activeTab === 'general' }" @click="activeTab = 'general'">通用設置</div>
        <div class="menu-item" :class="{ active: activeTab === 'appearance' }" @click="activeTab = 'appearance'">外貌定製</div>
        <div class="menu-item" :class="{ active: activeTab === 'system' }" @click="activeTab = 'system'">系統狀態</div>
        <div class="menu-item" :class="{ active: activeTab === 'tools' }" @click="activeTab = 'tools'">Tool管理</div>
        <div class="menu-item" :class="{ active: activeTab === 'skills' }" @click="activeTab = 'skills'">Skill管理</div>
      </div>

      <div class="content">
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
              <input type="checkbox" :checked="isModelVisible" @change="$emit('toggle-model')" />
              <span>顯示 Live2D 模型</span>
            </label>
          </div>

          <div class="section">
            <div class="section-title">INITIAL SETUP / 初始設定</div>
            <p class="desc">調整模型在屏幕上的初始位置和大小。</p>
            <div class="btn-group">
              <button class="action-btn" :class="{ 'active-red': isSetupMode }" @click="toggleSetupMode">
                {{ isSetupMode ? '設定完成' : '開始設定' }}
              </button>
              <button class="action-btn secondary" @click="$emit('reset-setup')">重置位置</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">TRACKING / 視線追蹤</div>
            <p class="desc">點擊模型表面設置視線追蹤的參考中心點。</p>
            <div class="btn-group">
              <button class="action-btn" :class="{ 'active-red': isTrackingSetupMode }" @click="toggleTrackingSetup">
                {{ isTrackingSetupMode ? '設定完成' : '開始設定' }}
              </button>
              <button class="action-btn secondary" @click="$emit('reset-tracking-setup')">重置中心</button>
            </div>
          </div>

          <div class="section" style="text-align: right; margin-top: 30px; border: none;">
            <button class="quit-btn" @click="quitApp">退出應用</button>
          </div>
        </div>

        <div v-if="activeTab === 'appearance'" class="tab-content">
          <div class="section">
            <div class="section-title">ACCESSORIES / 配飾與表情</div>
            <div class="appearance-list">
              <label v-for="file in appearance.APPEARANCE_FILES" :key="file" class="app-item">
                <input type="checkbox" v-model="appearance.appearanceEnabled.value[file]" @change="appearance.onAppearanceToggle(file, core)" />
                <span>{{ appearance.displayAppearanceName(file) }}</span>
              </label>
            </div>
            <button class="reset-btn" @click="$emit('reset-model')">重置所有外貌</button>
          </div>
        </div>

        <div v-if="activeTab === 'system'" class="tab-content">
          <div class="section">
            <div class="section-title">USER STATUS / 用戶狀態</div>
            <div class="status-row">
              <div class="status-indicator" :class="{ active: isLoggedIn }"></div>
              <span class="status-text">{{ isLoggedIn ? '已登錄 (Online)' : '未連接 (Offline)' }}</span>
              <button v-if="isLoggedIn" class="logout-btn" @click="$emit('logout')">退出登錄</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">WINDOW / 窗口設定</div>
            <label class="app-item">
              <input type="checkbox" v-model="isAlwaysOnTop" @change="toggleAlwaysOnTop" />
              <span>窗口置頂 (Always On Top)</span>
            </label>
          </div>

          <div class="section">
            <div class="section-title">RHYTHM / 律動</div>
            <label class="app-item">
              <input type="checkbox" :checked="rhythm.showSystemAudioListening.value" @change="rhythm.toggleSystemAudio(core, { value: true })" />
              <span>開啟系統音頻律動 (Beta)</span>
            </label>
          </div>
        </div>

        <div v-if="activeTab === 'tools'" class="tab-content" style="height: 100%;">
          <ToolManager @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')" />
        </div>

        <div v-if="activeTab === 'skills'" class="tab-content" style="height: 100%;">
          <SkillManager @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')" />
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
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { useTheme } from '../composables/useTheme';
import ToolManager from '../views/settings/ToolManager.vue';
import SkillManager from '../views/settings/SkillManager.vue';

defineProps([
  'core', 'model', 'appearance', 'rhythm',
  'isLoggedIn', 'isSetupMode', 'isTrackingSetupMode',
  'isModelVisible'
]);
const emit = defineEmits([
  'close', 'reset-model', 'toggle-setup', 'toggle-tracking-setup',
  'reset-setup', 'reset-tracking-setup',
  'mouseenter', 'mouseleave', 'toggle-model', 'logout'
]);

const x = ref(window.innerWidth / 2 - 320);
const y = ref(window.innerHeight / 2 - 240);
const width = ref(640);
const height = ref(480);
const minWidth = 420;
const minHeight = 320;

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
  if (e.target.closest('.close-btn') || e.target.closest('.header-btn')) return;
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
  x.value = Math.min(Math.max(e.clientX - dragOffset.x, -500), maxX);
  y.value = Math.min(Math.max(e.clientY - dragOffset.y, -350), maxY);
}
function stopDrag() {
  isDragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}

let resizeStart = { x: 0, y: 0 };
let initialSize = { w: 0, h: 0 };
let initialPos = { x: 0, y: 0 };
let resizeDir = '';

function startResize(e, dir) {
  if (isFullscreen.value) return;
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
    x.value = initialPos.x + (initialSize.w - newWidth);
  }
}

function stopResize() {
  window.removeEventListener('mousemove', onResize);
  window.removeEventListener('mouseup', stopResize);
}

const activeTab = ref('general');

const { THEMES, currentTheme, applyTheme } = useTheme();
const selectedTheme = ref(currentTheme.value);
const themes = THEMES;

function applyThemeSetting() {
  applyTheme(selectedTheme.value);
}
function toggleSetupMode() {
  emit('toggle-setup');
}
function toggleTrackingSetup() {
  emit('toggle-tracking-setup');
}
function quitApp() {
  window.desktopApi?.quit?.();
}

const isAlwaysOnTop = ref(true);
function toggleAlwaysOnTop() {
  window.desktopApi?.setAlwaysOnTop?.(isAlwaysOnTop.value);
}

onMounted(async () => {
  try {
    if (window?.desktopApi?.setAlwaysOnTop) {
      window.desktopApi.setAlwaysOnTop(isAlwaysOnTop.value);
    }
  } catch {}
});

onBeforeUnmount(() => {
  stopDrag();
  stopResize();
});
</script>

<style scoped>
.settings-panel {
  position: fixed;
  background:
    radial-gradient(circle at top right, rgba(0,255,200,0.1), transparent 36%),
    radial-gradient(circle at bottom left, rgba(0,170,255,0.08), transparent 32%),
    linear-gradient(145deg, rgba(6,12,22,0.95), rgba(10,16,30,0.94));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 10px;
  color: var(--text-main, #fff);
  display: flex;
  flex-direction: column;
  z-index: 9500;
  box-shadow:
    0 20px 50px rgba(0,0,0,0.8),
    0 0 24px rgba(0,255,200,0.14),
    inset 0 0 24px rgba(255,255,255,0.02);
  backdrop-filter: blur(12px) saturate(115%);
  overflow: hidden;
}
.settings-panel::before {
  content: "";
  position: absolute;
  inset: -35% -15%;
  background: conic-gradient(from 90deg, rgba(0,255,200,0.05), transparent 45%, rgba(0,170,255,0.05), transparent 80%);
  animation: panelAura 11s linear infinite;
  pointer-events: none;
}
.settings-panel.fullscreen {
  border-radius: 0;
}
.panel-header {
  padding: 12px 15px;
  background: rgba(0,0,0,0.24);
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: bold;
  letter-spacing: 1px;
  color: var(--primary, #00ffc8);
  cursor: move;
  user-select: none;
  z-index: 2;
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
  transition: 0.2s;
}
.header-btn:hover {
  border-color: var(--primary, #00ffc8);
  box-shadow: 0 0 8px rgba(0,255,200,0.25);
}
.close-btn {
  background: none;
  border: none;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
  transition: transform 0.2s;
}
.close-btn:hover { transform: scale(1.08); }

.panel-body {
  flex: 1;
  display: flex;
  overflow: hidden;
  z-index: 1;
}

.sidebar {
  width: 144px;
  background: linear-gradient(180deg, rgba(0,0,0,0.36), rgba(0,0,0,0.22));
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
  background: linear-gradient(90deg, rgba(0,255,200,0.12), rgba(0,170,255,0.08));
  color: var(--primary);
  border-left-color: var(--primary);
  box-shadow: inset 0 0 8px rgba(0,255,200,0.12);
}

.content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  position: relative;
}

.tab-content {
  animation: fadeIn 0.3s ease;
  height: 100%;
  display: flex;
  flex-direction: column;
}
@keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }

.section { margin-bottom: 25px; }
.section-title {
  font-size: 11px;
  color: var(--primary);
  opacity: 0.88;
  margin-bottom: 10px;
  letter-spacing: 1px;
  font-weight: bold;
  border-bottom: 1px solid var(--border);
  padding-bottom: 4px;
}
.desc { font-size: 12px; opacity: 0.66; margin-bottom: 10px; }

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
.apply-btn:hover {
  filter: brightness(1.08);
}

.btn-group {
  display: flex;
  gap: 10px;
}

.action-btn {
  flex: 1;
  padding: 8px;
  background: #28a745;
  color: white;
  border: none;
  cursor: pointer;
  transition: 0.2s;
  font-weight: bold;
  border-radius: 4px;
}
.action-btn:hover {
  filter: brightness(1.06);
}
.action-btn.active-red {
  background: #dc3545;
}
.action-btn.secondary {
  background: #6c757d;
}
.action-btn.secondary:hover {
  background: #5a6268;
}

.appearance-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 220px;
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
.reset-btn:hover {
  border-color: var(--primary, #00ffc8);
}

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
  background: var(--border);
  border-radius: 3px;
}
.content::-webkit-scrollbar-thumb:hover,
.appearance-list::-webkit-scrollbar-thumb:hover {
  background: var(--primary);
}

@keyframes panelAura {
  0% { transform: rotate(0deg); opacity: 0.65; }
  50% { opacity: 1; }
  100% { transform: rotate(360deg); opacity: 0.65; }
}
</style>
