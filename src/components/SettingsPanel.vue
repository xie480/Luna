<template>
  <div 
    class="settings-panel"
    ref="panelRef"
    :style="{ left: x + 'px', top: y + 'px' }"
  >
    <!-- 标题栏 (拖动区域) -->
    <div class="panel-header" @mousedown="startDrag">
      <span class="title">LUNA 控制中心</span>
      <button class="close-btn" @click="$emit('close')">×</button>
    </div>

    <div class="panel-body">
      <!-- 左侧侧边栏 -->
      <div class="sidebar">
        <div 
          v-for="item in menuItems" 
          :key="item.id"
          class="menu-item"
          :class="{ active: currentTab === item.id }"
          @click="currentTab = item.id"
        >
          {{ item.label }}
        </div>
      </div>

      <!-- 右侧内容区 -->
      <div class="content-area">
        
        <!-- 1. 基础设置 -->
        <div v-if="currentTab === 'basic'" class="tab-content">
          <h3>基础设置</h3>
          <div class="control-group">
            <label>应用控制</label>
            <div class="btn-row">
              <button class="action-btn danger" @click="quitApp">退出应用</button>
            </div>
          </div>
          
          <div class="control-group">
            <label>输入框主题</label>
            <select v-model="inputTheme" @change="saveSettings" class="theme-select">
              <option value="default">默认 (Default)</option>
              <option value="neon">霓虹 (Neon)</option>
              <option value="glass">毛玻璃 (Glass)</option>
              <option value="minimal">极简 (Minimal)</option>
            </select>
            <p class="hint">选择聊天输入框的视觉风格。</p>
          </div>

          <div class="control-group">
            <label>账户状态</label>
            <div class="status-text">
              {{ isLoggedIn ? '已登录' : '未登录' }}
            </div>
          </div>
        </div>

        <!-- 2. 调控 UI -->
        <div v-if="currentTab === 'ui'" class="tab-content">
          <h3>UI 调控</h3>
          <p class="hint">调整模型在屏幕上的显示位置与大小</p>
          
          <div class="control-group">
            <label>模型缩放: {{ modelScale.toFixed(2) }}</label>
            <input 
              type="range" 
              min="0.05" max="1.0" step="0.01" 
              v-model.number="modelScale"
              @input="updateModelTransform"
            />
          </div>

          <div class="control-group">
            <label>水平位置 (X): {{ modelX }}</label>
            <input 
              type="range" 
              min="-500" max="500" step="10" 
              v-model.number="modelX"
              @input="updateModelTransform"
            />
          </div>

          <div class="control-group">
            <label>垂直位置 (Y): {{ modelY }}</label>
            <input 
              type="range" 
              min="-500" max="500" step="10" 
              v-model.number="modelY"
              @input="updateModelTransform"
            />
          </div>
          
          <div class="control-group">
            <button class="action-btn" @click="resetModelTransform">重置模型状态</button>
          </div>
        </div>

        <!-- 3. 外貌设置 -->
        <div v-if="currentTab === 'appearance'" class="tab-content">
          <h3>外貌设置</h3>
          <p v-if="!core" class="warning-text">⚠️ 模型未就绪，无法应用变更</p>
          
          <div class="appearance-list">
            <div 
              v-for="file in appearance.APPEARANCE_FILES" 
              :key="file" 
              class="checkbox-item"
            >
              <label>
                <input 
                  type="checkbox" 
                  :checked="appearance.appearanceEnabled.value[file]" 
                  @change="(e) => handleAppearanceChange(file, e.target.checked)"
                  :disabled="!core"
                />
                <span class="label-text">{{ appearance.displayAppearanceName(file) }}</span>
              </label>
            </div>
          </div>
        </div>

        <!-- 4. 音乐律动 -->
        <div v-if="currentTab === 'rhythm'" class="tab-content">
          <h3>音乐律动</h3>
          <div class="control-group">
            <label class="switch-label">
              <input 
                type="checkbox" 
                :checked="rhythm.showSystemAudioListening.value"
                @change="toggleRhythm"
              />
              <span>启用系统音频监听</span>
            </label>
          </div>
          <p class="hint">开启后，Luna 将随着系统播放的音乐节奏律动。</p>
          <p class="hint" v-if="rhythm.showSystemAudioListening.value">🎵 正在监听音频...</p>
        </div>

        <!-- 5. 历史记录 -->
        <div v-if="currentTab === 'history'" class="tab-content">
          <h3>历史记录</h3>
          <div class="control-group">
            <input type="date" v-model="historyDate" @change="fetchHistory" class="date-input" />
          </div>
          <div class="history-logs">
            <div v-for="(log, idx) in historyLogs" :key="idx" class="log-item" :class="log.role">
              <span class="role-tag">{{ log.role === 'user' ? 'YOU' : 'LUNA' }}</span>
              <span class="log-content">{{ log.content }}</span>
            </div>
            <div v-if="historyLogs.length === 0" class="empty-logs">该日期暂无记录</div>
          </div>
        </div>

      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue';

const props = defineProps({
  core: Object,       // Live2D Core
  model: Object,      // PIXI Live2D Model (for scale/position)
  appearance: Object, // useAppearance return value
  rhythm: Object,     // useRhythm return value
  isLoggedIn: Boolean
});

const emit = defineEmits(['close', 'reset-model', 'theme-change']);

// === 菜单配置 ===
const currentTab = ref('basic');
const menuItems = [
  { id: 'basic', label: '基础设置' },
  { id: 'ui', label: '调控 UI' },
  { id: 'appearance', label: '外貌设置' },
  { id: 'rhythm', label: '音乐律动' },
  { id: 'history', label: '历史记录' }
];

// === 拖动逻辑 ===
const x = ref(window.innerWidth / 2 - 300);
const y = ref(window.innerHeight / 2 - 225);
const panelRef = ref(null);
let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
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

// === 业务逻辑 ===

// 1. 基础 & 主题
const inputTheme = ref('default');

function quitApp() {
  if (window.desktopApi) window.desktopApi.quit();
}

// 2. UI 调控 (位置与大小)
const modelScale = ref(0.1);
const modelX = ref(0);
const modelY = ref(0);

// 初始化加载设置
onMounted(() => {
  loadSettings();
  
  // 同步当前模型状态
  if (props.model) {
    // 如果 localStorage 没存，就用模型当前的
    if (!localStorage.getItem('luna_model_scale')) modelScale.value = props.model.scale.x;
    if (!localStorage.getItem('luna_model_x')) modelX.value = props.model.x;
    if (!localStorage.getItem('luna_model_y')) modelY.value = props.model.y;
    
    // 强制应用一次保存的设置
    updateModelTransform();
  }
});

function loadSettings() {
  const savedTheme = localStorage.getItem('luna_input_theme');
  if (savedTheme) {
    inputTheme.value = savedTheme;
    emit('theme-change', savedTheme); // 通知父组件应用主题
  }

  const savedScale = localStorage.getItem('luna_model_scale');
  if (savedScale) modelScale.value = parseFloat(savedScale);

  const savedX = localStorage.getItem('luna_model_x');
  if (savedX) modelX.value = parseFloat(savedX);

  const savedY = localStorage.getItem('luna_model_y');
  if (savedY) modelY.value = parseFloat(savedY);
}

function saveSettings() {
  localStorage.setItem('luna_input_theme', inputTheme.value);
  localStorage.setItem('luna_model_scale', modelScale.value);
  localStorage.setItem('luna_model_x', modelX.value);
  localStorage.setItem('luna_model_y', modelY.value);
  
  // 触发主题变更事件
  emit('theme-change', inputTheme.value);
}

function updateModelTransform() {
  if (props.model) {
    props.model.scale.set(modelScale.value);
    props.model.x = modelX.value;
    props.model.y = modelY.value;
    saveSettings();
  }
}

function resetModelTransform() {
  modelScale.value = 0.1;
  modelX.value = 0;
  modelY.value = 0;
  updateModelTransform();
  emit('reset-model');
}

// 3. 外貌
function handleAppearanceChange(file, isChecked) {
  if (!props.core || !props.appearance) return;
  props.appearance.appearanceEnabled.value[file] = isChecked;
  props.appearance.onAppearanceToggle(file, props.core);
}

// 4. 律动
function toggleRhythm() {
  if (props.rhythm && props.core) {
    const dummyTracking = { value: true }; 
    props.rhythm.toggleSystemAudio(props.core, dummyTracking);
  }
}

// 5. 历史记录
const historyDate = ref(new Date().toISOString().split('T')[0]);
const historyLogs = ref([]);

async function fetchHistory() {
  if (!historyDate.value || !window.desktopApi) return;
  try {
    const dateStr = historyDate.value.replace(/-/g, ':');
    const res = await window.desktopApi.history(dateStr);
    const rawList = res?.data ?? res;
    historyLogs.value = (Array.isArray(rawList) ? rawList : [])
      .filter((item) => typeof item === "string")
      .map((item) => {
        const parts = item.split(":");
        const role = parts[0];
        const content = parts.slice(1, -3).join(":");
        return { role, content };
      });
  } catch (e) {
    console.error("获取历史失败", e);
    historyLogs.value = [];
  }
}

onMounted(() => {
  fetchHistory();
});
</script>

<style scoped>
.settings-panel {
  position: fixed;
  width: 600px;
  height: 450px;
  background: rgba(255, 255, 255, 0.98);
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.25);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 10000;
  font-family: "Segoe UI", sans-serif;
  border: 1px solid #eee;
  backdrop-filter: blur(10px);
}

.panel-header {
  height: 45px;
  background: #f5f7fa;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  cursor: move;
  border-bottom: 1px solid #e4e7ed;
  user-select: none;
}

.title {
  font-weight: 600;
  color: #606266;
  font-size: 14px;
  letter-spacing: 1px;
}

.close-btn {
  border: none;
  background: none;
  font-size: 24px;
  color: #909399;
  cursor: pointer;
  line-height: 1;
  padding: 0 5px;
}
.close-btn:hover { color: #f56c6c; }

.panel-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.sidebar {
  width: 130px;
  background: #fafafa;
  border-right: 1px solid #e4e7ed;
  padding-top: 10px;
  display: flex;
  flex-direction: column;
}

.menu-item {
  padding: 12px 20px;
  cursor: pointer;
  color: #606266;
  transition: all 0.2s;
  font-size: 14px;
  border-left: 3px solid transparent;
}

.menu-item:hover {
  background: #ecf5ff;
  color: #409eff;
}

.menu-item.active {
  background: #ecf5ff;
  color: #409eff;
  border-left: 3px solid #409eff;
  font-weight: 600;
}

.content-area {
  flex: 1;
  padding: 25px;
  overflow-y: auto;
  position: relative;
}

.tab-content h3 {
  margin-top: 0;
  margin-bottom: 20px;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 10px;
  font-size: 16px;
  font-weight: 600;
}

.control-group {
  margin-bottom: 20px;
}

.control-group label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
}

.theme-select {
  width: 100%;
  padding: 8px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  color: #606266;
  outline: none;
}

.action-btn {
  padding: 8px 16px;
  background: #409eff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.2s;
}
.action-btn:hover { background: #66b1ff; }

.action-btn.danger {
  background: #f56c6c;
}
.action-btn.danger:hover { background: #f78989; }

.hint {
  color: #909399;
  font-size: 12px;
  margin-top: 5px;
}

.warning-text {
  color: #e6a23c;
  font-size: 13px;
  margin-bottom: 10px;
  background: #fdf6ec;
  padding: 8px;
  border-radius: 4px;
}

/* 外貌列表 */
.appearance-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  max-height: 320px;
  overflow-y: auto;
}

.checkbox-item label {
  display: flex;
  align-items: center;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  user-select: none;
}

.checkbox-item input {
  margin-right: 8px;
}

/* 历史记录 */
.date-input {
  padding: 6px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  color: #606266;
}

.history-logs {
  margin-top: 10px;
  height: 280px;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  padding: 10px;
  border-radius: 4px;
  background: #fafafa;
}

.log-item {
  margin-bottom: 10px;
  font-size: 13px;
  line-height: 1.5;
  display: flex;
  flex-direction: column;
}

.log-item.user { align-items: flex-end; }
.log-item.assistant { align-items: flex-start; }

.role-tag {
  font-size: 10px;
  color: #999;
  margin-bottom: 2px;
}

.log-content {
  background: #fff;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
  max-width: 80%;
  word-break: break-word;
}

.log-item.user .log-content {
  background: #ecf5ff;
  border-color: #b3d8ff;
  color: #409eff;
}

.empty-logs {
  text-align: center;
  color: #999;
  margin-top: 40px;
  font-size: 13px;
}

.switch-label {
  display: flex;
  align-items: center;
  cursor: pointer;
}
.switch-label input { margin-right: 8px; }
</style>
