<template>
  <div class="settings-panel" @mouseenter="$emit('mouseenter')" @mouseleave="$emit('mouseleave')">
    <div class="panel-header">
      <span>SYSTEM SETTINGS</span>
      <button class="close-btn" @click="$emit('close')">×</button>
    </div>

    <div class="panel-content">
      <!-- 1. 用戶狀態 -->
      <div class="section">
        <div class="section-title">USER STATUS</div>
        <div class="status-row">
          <div class="status-indicator" :class="{ active: isLoggedIn }"></div>
          <span class="status-text">{{ isLoggedIn ? '已登錄' : '未連接' }}</span>
        </div>
      </div>

      <!-- 2. 主題設置 -->
      <div class="section">
        <div class="section-title">THEME</div>
        <div class="theme-control">
          <select v-model="selectedTheme">
            <option v-for="(t, key) in themes" :key="key" :value="key">{{ t.name }}</option>
          </select>
          <button class="apply-btn" @click="applyThemeSetting">應用</button>
        </div>
      </div>

      <!-- 3. 初始設定 (位置/大小) -->
      <div class="section">
        <div class="section-title">INITIAL SETUP</div>
        <p class="desc">調整模型在屏幕上的初始位置和大小。</p>
        <button 
          class="action-btn" 
          :class="{ 'active-red': isSetupMode }"
          @click="toggleSetupMode"
        >
          {{ isSetupMode ? '設定完成' : '開始設定' }}
        </button>
      </div>

      <!-- 4. 滑鼠追蹤設定 -->
      <div class="section">
        <div class="section-title">TRACKING SETUP</div>
        <p class="desc">點擊模型表面設置視線追蹤的參考中心點。</p>
        <button 
          class="action-btn" 
          :class="{ 'active-red': isTrackingSetupMode }"
          @click="toggleTrackingSetup"
        >
          {{ isTrackingSetupMode ? '設定完成' : '開始設定' }}
        </button>
      </div>

      <!-- 5. 外貌設置 -->
      <div class="section">
        <div class="section-title">APPEARANCE</div>
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
        <button class="reset-btn" @click="$emit('reset-model')">重置外貌</button>
      </div>

      <!-- 6. 律動設置 -->
      <div class="section">
        <div class="section-title">RHYTHM</div>
        <label class="app-item">
          <input 
            type="checkbox" 
            :checked="rhythm.showSystemAudioListening.value"
            @change="rhythm.toggleSystemAudio(core, { value: true })"
          />
          <span>開啟系統音頻律動</span>
        </label>
      </div>
    </div>

    <!-- 退出按鈕 (右下角) -->
    <button class="quit-btn" @click="quitApp">退出應用</button>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useTheme } from '../composables/useTheme';

const props = defineProps([
  'core', 'model', 'appearance', 'rhythm', 
  'isLoggedIn', 'isSetupMode', 'isTrackingSetupMode'
]);
const emit = defineEmits([
  'close', 'reset-model', 'toggle-setup', 'toggle-tracking-setup', 
  'mouseenter', 'mouseleave'
]);

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
</script>

<style scoped>
.settings-panel {
  position: fixed;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  width: 400px;
  height: 600px;
  background: var(--bg-panel, rgba(5,10,19,0.95));
  border: 1px solid var(--border, rgba(0,255,200,0.3));
  border-radius: 8px;
  color: var(--text-main, #fff);
  display: flex;
  flex-direction: column;
  z-index: 9500;
  box-shadow: 0 0 40px rgba(0,0,0,0.8);
}

.panel-header {
  padding: 15px;
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  font-weight: bold;
  letter-spacing: 1px;
  color: var(--primary, #00ffc8);
}
.close-btn { background: none; border: none; color: inherit; font-size: 20px; cursor: pointer; }

.panel-content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}

.section { margin-bottom: 25px; }
.section-title {
  font-size: 10px;
  color: var(--primary);
  opacity: 0.7;
  margin-bottom: 8px;
  letter-spacing: 1px;
}
.desc { font-size: 12px; opacity: 0.6; margin-bottom: 8px; }

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

/* 主題控制 */
.theme-control { display: flex; gap: 10px; }
select {
  flex: 1;
  background: rgba(0,0,0,0.5);
  border: 1px solid var(--border);
  color: white;
  padding: 4px;
  outline: none;
}
.apply-btn {
  background: var(--primary);
  color: black;
  border: none;
  padding: 4px 12px;
  cursor: pointer;
  font-size: 12px;
  font-weight: bold;
}

/* 綠色/紅色按鈕 */
.action-btn {
  width: 100%;
  padding: 8px;
  background: #00aa00; /* 綠色 */
  color: white;
  border: none;
  cursor: pointer;
  transition: 0.2s;
  font-weight: bold;
}
.action-btn.active-red {
  background: #aa0000; /* 紅色 */
}

/* 外貌列表 */
.appearance-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 150px;
  overflow-y: auto;
  margin-bottom: 10px;
  padding-right: 5px;
}
.app-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  cursor: pointer;
}
.reset-btn {
  background: rgba(255,255,255,0.1);
  border: 1px solid var(--border);
  color: var(--text-main);
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
}

/* 退出按鈕 */
.quit-btn {
  position: absolute;
  bottom: 15px;
  right: 15px;
  background: rgba(255, 50, 50, 0.2);
  border: 1px solid rgba(255, 50, 50, 0.5);
  color: #ffaaaa;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 12px;
  transition: 0.2s;
}
.quit-btn:hover { background: rgba(255, 50, 50, 0.4); }
</style>
