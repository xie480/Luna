<template>
  <div
    class="settings-panel"
    :class="{ fullscreen: isFullscreen }"
    :style="panelStyle"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="panel-header" @mousedown="startDrag">
      <div class="header-left">
        <span class="header-badge">SYS</span>
        <span class="header-title">SYSTEM SETTINGS</span>
      </div>
      <div class="header-actions">
        <button class="header-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
          {{ isFullscreen ? "🗗" : "🗖" }}
        </button>
        <button class="close-btn" @click="$emit('close')">×</button>
      </div>
    </div>

    <div class="panel-body">
      <div class="sidebar">
        <div class="menu-item" :class="{ active: activeTab === 'general' }" @click="activeTab = 'general'">
          <span class="menu-icon">▸</span>通用設置
        </div>
        <div class="menu-item" :class="{ active: activeTab === 'appearance' }" @click="activeTab = 'appearance'">
          <span class="menu-icon">▸</span>外貌定製
        </div>
        <div class="menu-item" :class="{ active: activeTab === 'system' }" @click="activeTab = 'system'">
          <span class="menu-icon">▸</span>系統狀態
        </div>
        <div class="menu-item" :class="{ active: activeTab === 'tools' }" @click="activeTab = 'tools'">
          <span class="menu-icon">▸</span>Tool管理
        </div>
        <div class="menu-item" :class="{ active: activeTab === 'skills' }" @click="activeTab = 'skills'">
          <span class="menu-icon">▸</span>Skill管理
        </div>
      </div>

      <div class="content">
        <div v-if="activeTab === 'general'" class="tab-content">
          <div class="section">
            <div class="section-title">THEME <em>/ 主題</em></div>
            <div class="theme-control">
              <select v-model="selectedTheme">
                <option v-for="(t, key) in themes" :key="key" :value="key">{{ t.name }}</option>
              </select>
              <button class="apply-btn" @click="applyThemeSetting">應用</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">MODEL DISPLAY <em>/ 模型顯示</em></div>
            <label class="app-item">
              <input type="checkbox" :checked="isModelVisible" @change="$emit('toggle-model')" />
              <span>顯示 Live2D 模型</span>
            </label>
          </div>

          <div class="section">
            <div class="section-title">INITIAL SETUP <em>/ 初始設定</em></div>
            <p class="desc">調整模型在屏幕上的初始位置和大小。</p>
            <div class="btn-group">
              <button class="action-btn" :class="{ 'active-red': isSetupMode }" @click="toggleSetupMode">
                {{ isSetupMode ? '設定完成' : '開始設定' }}
              </button>
              <button class="action-btn secondary" @click="$emit('reset-setup')">重置位置</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">TRACKING <em>/ 視線追蹤</em></div>
            <p class="desc">點擊模型表面設置視線追蹤的參考中心點。</p>
            <div class="btn-group">
              <button class="action-btn" :class="{ 'active-red': isTrackingSetupMode }" @click="toggleTrackingSetup">
                {{ isTrackingSetupMode ? '設定完成' : '開始設定' }}
              </button>
              <button class="action-btn secondary" @click="$emit('reset-tracking-setup')">重置中心</button>
            </div>
          </div>

          <div class="section quit-section">
            <button class="quit-btn" @click="quitApp">退出應用</button>
          </div>
        </div>

        <div v-if="activeTab === 'appearance'" class="tab-content">
          <div class="section">
            <div class="section-title">ACCESSORIES <em>/ 配飾與表情</em></div>
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
            <div class="section-title">USER STATUS <em>/ 用戶狀態</em></div>
            <div class="status-row">
              <div class="status-indicator" :class="{ active: isLoggedIn }"></div>
              <span class="status-text">{{ isLoggedIn ? '已登錄 (Online)' : '未連接 (Offline)' }}</span>
              <button v-if="isLoggedIn" class="logout-btn" @click="$emit('logout')">退出登錄</button>
            </div>
          </div>

          <div class="section">
            <div class="section-title">WINDOW <em>/ 窗口設定</em></div>
            <label class="app-item">
              <input type="checkbox" v-model="isAlwaysOnTop" @change="toggleAlwaysOnTop" />
              <span>窗口置頂 (Always On Top)</span>
            </label>
          </div>

          <div class="section">
            <div class="section-title">RHYTHM <em>/ 律動</em></div>
            <label class="app-item">
              <input type="checkbox" :checked="rhythm.showSystemAudioListening.value" @change="rhythm.toggleSystemAudio(core, { value: true })" />
              <span>開啟系統音頻律動 (Beta)</span>
            </label>
          </div>
        </div>

        <div v-if="activeTab === 'tools'" class="tab-content fill">
          <ToolManager @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')" />
        </div>

        <div v-if="activeTab === 'skills'" class="tab-content fill">
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
import { useTheme } from '../../composables/useTheme';
import ToolManager from '../../views/settings/ToolManager.vue';
import SkillManager from '../../views/settings/SkillManager.vue';

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
  return {
    left: x.value + 'px',
    top: y.value + 'px',
    width: width.value + 'px',
    height: height.value + 'px'
  };
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

let resizeDir = '';
let resizeStart = { x: 0, y: 0 };
let initialRect = { x: 0, y: 0, w: 0, h: 0 };

function startResize(e, dir) {
  if (isFullscreen.value) return;
  resizeDir = dir;
  resizeStart = { x: e.clientX, y: e.clientY };
  initialRect = { x: x.value, y: y.value, w: width.value, h: height.value };
  window.addEventListener('mousemove', onResize);
  window.addEventListener('mouseup', stopResize);
}
function onResize(e) {
  const dx = e.clientX - resizeStart.x;
  const dy = e.clientY - resizeStart.y;
  if (resizeDir === 'se') {
    width.value = Math.max(minWidth, initialRect.w + dx);
    height.value = Math.max(minHeight, initialRect.h + dy);
  } else if (resizeDir === 'sw') {
    const newWidth = Math.max(minWidth, initialRect.w - dx);
    width.value = newWidth;
    height.value = Math.max(minHeight, initialRect.h + dy);
    x.value = initialRect.x + (initialRect.w - newWidth);
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

function applyThemeSetting() { applyTheme(selectedTheme.value); }
function toggleSetupMode() { emit('toggle-setup'); }
function toggleTrackingSetup() { emit('toggle-tracking-setup'); }
function quitApp() { window.desktopApi?.quit?.(); }

const isAlwaysOnTop = ref(true);
function toggleAlwaysOnTop() { window.desktopApi?.setAlwaysOnTop?.(isAlwaysOnTop.value); }

onMounted(async () => {
  try {
    // 可擴展：讀取初始置頂狀態
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
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(circle at 8% 12%, rgba(0,255,200,0.07), transparent 38%),
    radial-gradient(circle at 92% 88%, rgba(0,170,255,0.06), transparent 36%),
    linear-gradient(150deg, rgba(7,12,22,0.97), rgba(4,8,16,0.97));
  border: 1px solid rgba(0,255,200,0.2);
  border-radius: 10px;
  box-shadow:
    0 20px 60px rgba(0,0,0,0.8),
    0 0 0 1px rgba(255,255,255,0.03) inset,
    0 1px 0 rgba(255,255,255,0.05) inset;
  color: var(--text-main, #e8fff8);
  z-index: 9500;
  overflow: hidden;
  backdrop-filter: blur(18px) saturate(140%);
  font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
}
.settings-panel.fullscreen {
  border-radius: 0;
}

/* ── Header ── */
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: rgba(0,0,0,0.28);
  border-bottom: 1px solid rgba(0,255,200,0.14);
  cursor: move;
  user-select: none;
  flex-shrink: 0;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-badge {
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: rgba(0,0,0,0.85);
  background: var(--primary, #00ffc8);
  padding: 2px 6px;
  border-radius: 3px;
}
.header-title {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: rgba(0,255,200,0.9);
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.header-btn {
  border: 1px solid rgba(255,255,255,0.18);
  background: rgba(255,255,255,0.07);
  color: var(--text-main, #fff);
  width: 24px;
  height: 22px;
  border-radius: 4px;
  cursor: pointer;
  line-height: 1;
  font-size: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.header-btn:hover {
  border-color: var(--primary, #00ffc8);
  box-shadow: 0 0 8px rgba(0,255,200,0.2);
}
.close-btn {
  background: none;
  border: none;
  color: rgba(255,255,255,0.55);
  font-size: 20px;
  cursor: pointer;
  line-height: 1;
  padding: 0 2px;
  transition: color 0.2s, transform 0.2s;
}
.close-btn:hover { color: #fff; transform: scale(1.1); }

/* ── Body Layout ── */
.panel-body {
  flex: 1;
  display: flex;
  overflow: hidden;
  min-height: 0;
}

/* ── Sidebar ── */
.sidebar {
  width: 120px;
  flex-shrink: 0;
  background: rgba(0,0,0,0.22);
  border-right: 1px solid rgba(0,255,200,0.1);
  display: flex;
  flex-direction: column;
  padding: 8px 0;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  font-size: 12px;
  color: var(--text-dim, rgba(232,255,248,0.55));
  cursor: pointer;
  border-left: 2px solid transparent;
  transition: background 0.18s, color 0.18s, border-color 0.18s;
}
.menu-item:hover {
  background: rgba(0,255,200,0.06);
  color: var(--text-main, #e8fff8);
}
.menu-item.active {
  background: linear-gradient(90deg, rgba(0,255,200,0.1), rgba(0,255,200,0.04));
  color: var(--primary, #00ffc8);
  border-left-color: var(--primary, #00ffc8);
}
.menu-icon {
  font-size: 9px;
  opacity: 0.6;
  flex-shrink: 0;
}

/* ── Content ── */
.content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 16px;
  min-width: 0;
  min-height: 0;
}
.tab-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.tab-content.fill {
  height: 100%;
  overflow: hidden;
}

/* ── Section ── */
.section {
  border: 1px solid rgba(0,255,200,0.1);
  border-radius: 7px;
  padding: 12px 14px;
  background: rgba(0,0,0,0.18);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-title {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  color: var(--primary, #00ffc8);
  opacity: 0.85;
  text-transform: uppercase;
}
.section-title em {
  font-style: normal;
  color: var(--text-dim, rgba(232,255,248,0.45));
  font-weight: 400;
  letter-spacing: 0.04em;
  margin-left: 2px;
}
.desc {
  font-size: 11px;
  color: var(--text-dim, rgba(232,255,248,0.5));
  line-height: 1.5;
  margin: 0;
}

/* ── Controls ── */
.theme-control {
  display: flex;
  gap: 8px;
  align-items: center;
}
.theme-control select {
  flex: 1;
  background: rgba(0,0,0,0.35);
  border: 1px solid rgba(0,255,200,0.18);
  color: var(--text-main, #e8fff8);
  border-radius: 5px;
  padding: 6px 10px;
  font-size: 12px;
  outline: none;
  transition: border-color 0.2s;
}
.theme-control select:focus {
  border-color: rgba(0,255,200,0.45);
}
.apply-btn {
  background: var(--primary, #00ffc8);
  color: #000;
  border: none;
  border-radius: 5px;
  padding: 6px 14px;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  letter-spacing: 0.06em;
  transition: filter 0.18s, transform 0.15s;
}
.apply-btn:hover { filter: brightness(1.1); transform: translateY(-1px); }

.app-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-main, #e8fff8);
  cursor: pointer;
  padding: 2px 0;
}
.app-item input[type="checkbox"] {
  accent-color: var(--primary, #00ffc8);
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  cursor: pointer;
}

.btn-group {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.action-btn {
  background: rgba(0,255,200,0.1);
  border: 1px solid rgba(0,255,200,0.28);
  color: var(--primary, #00ffc8);
  border-radius: 5px;
  padding: 6px 14px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  letter-spacing: 0.06em;
  transition: background 0.18s, border-color 0.18s, transform 0.15s;
}
.action-btn:hover {
  background: rgba(0,255,200,0.18);
  transform: translateY(-1px);
}
.action-btn.active-red {
  background: rgba(255,60,60,0.18);
  border-color: rgba(255,60,60,0.5);
  color: #ff7070;
}
.action-btn.secondary {
  background: rgba(255,255,255,0.05);
  border-color: rgba(255,255,255,0.14);
  color: var(--text-dim, rgba(232,255,248,0.55));
}
.action-btn.secondary:hover {
  background: rgba(255,255,255,0.1);
  color: var(--text-main, #e8fff8);
}

/* ── Status Row ── */
.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.status-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255,255,255,0.2);
  flex-shrink: 0;
  transition: background 0.3s, box-shadow 0.3s;
}
.status-indicator.active {
  background: #22c55e;
  box-shadow: 0 0 8px rgba(34,197,94,0.6);
}
.status-text {
  font-size: 12px;
  color: var(--text-main, #e8fff8);
  flex: 1;
}
.logout-btn {
  background: rgba(255,80,80,0.12);
  border: 1px solid rgba(255,80,80,0.35);
  color: #ff8080;
  border-radius: 5px;
  padding: 4px 12px;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.18s;
}
.logout-btn:hover { background: rgba(255,80,80,0.22); }

/* ── Appearance ── */
.appearance-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  overflow-y: auto;
  padding-right: 4px;
}
.reset-btn {
  margin-top: 6px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.14);
  color: var(--text-dim, rgba(232,255,248,0.55));
  border-radius: 5px;
  padding: 5px 12px;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.18s, color 0.18s;
}
.reset-btn:hover {
  background: rgba(255,255,255,0.12);
  color: var(--text-main, #e8fff8);
}

/* ── Quit ── */
.quit-section {
  border-color: rgba(255,60,60,0.15);
  background: rgba(255,60,60,0.04);
  align-items: flex-end;
}
.quit-btn {
  background: rgba(255,60,60,0.14);
  border: 1px solid rgba(255,60,60,0.38);
  color: #ff8080;
  border-radius: 5px;
  padding: 6px 18px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  letter-spacing: 0.08em;
  transition: background 0.18s, transform 0.15s;
}
.quit-btn:hover {
  background: rgba(255,60,60,0.26);
  transform: translateY(-1px);
}

/* ── Resize Handles ── */
.resize-handle {
  position: absolute;
  width: 14px;
  height: 14px;
  bottom: 0;
  z-index: 10;
}
.resize-handle.se { right: 0; cursor: se-resize; }
.resize-handle.sw { left: 0; cursor: sw-resize; }

/* ── Scrollbar ── */
.content::-webkit-scrollbar,
.appearance-list::-webkit-scrollbar {
  width: 6px;
}
.content::-webkit-scrollbar-track,
.appearance-list::-webkit-scrollbar-track {
  background: rgba(255,255,255,0.03);
  border-radius: 6px;
}
.content::-webkit-scrollbar-thumb,
.appearance-list::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, rgba(0,255,200,0.4), rgba(0,170,255,0.4));
  border-radius: 6px;
}
.content::-webkit-scrollbar-thumb:hover,
.appearance-list::-webkit-scrollbar-thumb:hover {
  background: linear-gradient(180deg, rgba(0,255,200,0.65), rgba(0,170,255,0.6));
}
</style>