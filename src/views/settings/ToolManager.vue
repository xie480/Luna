<template>
  <div class="tool-manager">
    <div class="header">
      <div class="section-title">MCP TOOLS / 工具管理</div>
      <button class="btn-primary" @click="openCreateModal">
        + 新增工具
      </button>
    </div>

    <!-- 工具列表 -->
    <div class="tool-list">
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="tools.length === 0" class="empty">暂无工具</div>
      
      <div v-else class="tool-card" v-for="tool in tools" :key="tool.id">
        <div class="card-header">
          <span class="tool-name">{{ tool.name }}</span>
          <span class="tool-version" v-if="tool.version">v{{ tool.version }}</span>
        </div>
        <div class="card-body">
          <p class="desc">{{ tool.description }}</p>
          <div class="meta">
            <span class="tag">Bean: {{ tool.beanName }}</span>
            <span class="tag">Method: {{ tool.methodName }}</span>
          </div>
        </div>
        <div class="card-footer">
          <button class="btn-text" @click="openEditModal(tool)">编辑</button>
          <button class="btn-text delete" @click="handleDelete(tool)">删除</button>
        </div>
      </div>
    </div>

    <!-- 独立遮罩层 -->
    <div v-if="showModal" class="modal-overlay" @click="closeModal"></div>

    <!-- 编辑/新增 弹窗 (可拖拽) -->
    <div 
      v-if="showModal" 
      class="modal"
      :style="{ left: modalX + 'px', top: modalY + 'px' }"
    >
      <!-- 弹窗头部 (拖拽区域) -->
      <div class="modal-header" @mousedown="startDrag">
        <h3>{{ isEdit ? '编辑工具' : '注册新工具' }}</h3>
        <button class="close-btn" @click="closeModal">×</button>
      </div>
      
      <!-- 弹窗内容 (可滚动) -->
      <div class="modal-body">
        <div class="form-group">
          <label>工具名称 (Name)*</label>
          <input v-model="form.name" placeholder="例如: web_search" />
        </div>

        <div class="form-group">
          <label>工具描述 (Description)*</label>
          <textarea v-model="form.description" placeholder="详细说明该工具的用途，供大模型理解..."></textarea>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label>Spring Bean 名称 (Bean Name)*</label>
            <input v-model="form.beanName" placeholder="例如: searchTools" />
          </div>
          <div class="form-group">
            <label>方法名称 (Method Name)*</label>
            <input v-model="form.methodName" placeholder="例如: executeSearch" />
          </div>
        </div>

        <div class="form-group">
          <label>输入参数结构 (Input Schema JSON)*</label>
          <textarea 
            v-model="form.inputSchema" 
            class="code-editor" 
            placeholder='{"type":"object", "properties": {}}'
          ></textarea>
        </div>

        <div class="form-group">
          <label>版本号 (Version)</label>
          <input v-model="form.version" placeholder="例如: 1.0.0" />
        </div>
      </div>

      <!-- 弹窗底部操作区 -->
      <div class="modal-actions">
        <button class="btn-secondary" @click="closeModal">取消</button>
        <button class="btn-primary" @click="handleSave">保存</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue';

const tools = ref([]);
const loading = ref(false);
const showModal = ref(false);
const isEdit = ref(false);

// 弹窗拖拽逻辑
const modalX = ref(window.innerWidth / 2 - 250);
const modalY = ref(window.innerHeight / 2 - 300);
let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
  if (e.target.closest('.close-btn')) return;
  isDragging = true;
  dragOffset.x = e.clientX - modalX.value;
  dragOffset.y = e.clientY - modalY.value;
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}

function onDrag(e) {
  if (!isDragging) return;
  modalX.value = e.clientX - dragOffset.x;
  modalY.value = e.clientY - dragOffset.y;
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}

// 表单数据
const form = reactive({
  id: '',
  name: '',
  description: '',
  beanName: '',
  methodName: '',
  inputSchema: '',
  version: '',
  owner: ''
});

onMounted(() => {
  fetchTools();
});

async function fetchTools() {
  loading.value = true;
  try {
    // 调用后端获取所有资源
    const resources = await window.mcpApi.listResources();
    // 过滤出类型为 TOOL 的资源
    tools.value = resources.filter(r => r.type === 'TOOL');
  } catch (err) {
    console.error("Failed to fetch tools:", err);
    alert("获取工具列表失败: " + err.message);
  } finally {
    loading.value = false;
  }
}

function openCreateModal() {
  isEdit.value = false;
  resetForm();
  // 居中显示
  modalX.value = window.innerWidth / 2 - 250;
  modalY.value = window.innerHeight / 2 - 300;
  showModal.value = true;
}

function openEditModal(tool) {
  isEdit.value = true;
  Object.assign(form, tool);
  // 确保 inputSchema 是字符串显示
  if (typeof form.inputSchema === 'object') {
    form.inputSchema = JSON.stringify(form.inputSchema, null, 2);
  }
  // 居中显示
  modalX.value = window.innerWidth / 2 - 250;
  modalY.value = window.innerHeight / 2 - 300;
  showModal.value = true;
}

function closeModal() {
  showModal.value = false;
}

function resetForm() {
  form.id = '';
  form.name = '';
  form.description = '';
  form.beanName = '';
  form.methodName = '';
  form.inputSchema = '{\n  "type": "object",\n  "properties": {}\n}';
  form.version = '';
  form.owner = '';
}

async function handleSave() {
  if (!form.name || !form.beanName || !form.methodName) {
    alert("请填写必填字段");
    return;
  }

  // 验证 JSON 格式
  try {
    JSON.parse(form.inputSchema);
  } catch (e) {
    alert("Input Schema 必须是有效的 JSON 格式");
    return;
  }

  try {
    if (isEdit.value) {
      await window.mcpApi.updateTool({ ...form });
    } else {
      await window.mcpApi.createTool({ ...form });
    }
    closeModal();
    fetchTools();
  } catch (err) {
    console.error("Save failed:", err);
    alert("保存失败: " + err.message);
  }
}

async function handleDelete(tool) {
  if (!confirm(`确定要删除工具 "${tool.name}" 吗？`)) return;
  
  try {
    await window.mcpApi.deleteTool(tool.id);
    fetchTools();
  } catch (err) {
    console.error("Delete failed:", err);
    alert("删除失败: " + err.message);
  }
}
</script>

<style scoped>
.tool-manager {
  color: var(--text-main, #eee);
  height: 100%;
  display: flex;
  flex-direction: column;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
  border-bottom: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  padding-bottom: 10px;
  flex-shrink: 0;
}

.section-title {
  font-size: 11px;
  color: var(--primary);
  opacity: 0.8;
  letter-spacing: 1px;
  font-weight: bold;
}

.tool-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 15px;
  overflow-y: auto;
  padding-right: 5px;
}

.tool-card {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  border-radius: 6px;
  padding: 15px;
  transition: transform 0.2s;
}

.tool-card:hover {
  transform: translateY(-2px);
  background: rgba(255, 255, 255, 0.08);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.tool-name {
  font-weight: bold;
  font-size: 1.1em;
  color: var(--primary, #4fd1c5);
}

.tool-version {
  font-size: 0.8em;
  background: rgba(0, 0, 0, 0.3);
  padding: 2px 6px;
  border-radius: 4px;
}

.desc {
  font-size: 0.9em;
  color: var(--text-dim, #aaa);
  margin-bottom: 10px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 15px;
}

.tag {
  font-size: 0.75em;
  background: var(--primary-dim, rgba(79, 209, 197, 0.1));
  color: var(--primary, #81e6d9);
  padding: 2px 6px;
  border-radius: 4px;
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  padding-top: 10px;
}

/* Buttons */
.btn-primary {
  background: var(--primary, #319795);
  color: #000;
  border: none;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: bold;
}
.btn-primary:hover { filter: brightness(1.1); }

.btn-secondary {
  background: #4a5568;
  color: white;
  border: none;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.btn-text {
  background: none;
  border: none;
  color: #63b3ed;
  cursor: pointer;
  font-size: 0.9em;
}
.btn-text:hover { text-decoration: underline; }
.btn-text.delete { color: #fc8181; }

/* Modal Overlay */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  z-index: 9998;
}

/* Draggable Modal */
.modal {
  position: fixed;
  background: var(--bg-panel, #1a202c);
  border-radius: 8px;
  width: 500px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border, #2d3748);
  box-shadow: 0 15px 40px rgba(0,0,0,0.8);
  z-index: 10000; /* 确保高于 SettingsPanel 的 9500 */
}

.modal-header {
  padding: 15px 20px;
  border-bottom: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: move;
  user-select: none;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px 8px 0 0;
}

.modal-header h3 {
  margin: 0;
  font-size: 14px;
  color: var(--primary);
  letter-spacing: 1px;
}

.close-btn {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 20px;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}
.close-btn:hover { color: #fff; }

.modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.form-group {
  margin-bottom: 15px;
}

.form-row {
  display: flex;
  gap: 15px;
}
.form-row .form-group { flex: 1; }

label {
  display: block;
  margin-bottom: 5px;
  font-size: 0.9em;
  color: var(--text-dim, #cbd5e0);
}

input, textarea {
  width: 100%;
  background: rgba(0,0,0,0.3);
  border: 1px solid var(--border, #4a5568);
  color: white;
  padding: 8px;
  border-radius: 4px;
  font-family: inherit;
  box-sizing: border-box;
}

input:focus, textarea:focus {
  outline: none;
  border-color: var(--primary, #4fd1c5);
}

textarea {
  min-height: 80px;
  resize: vertical;
}

.code-editor {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 0.9em;
  min-height: 120px;
}

.modal-actions {
  padding: 15px 20px;
  border-top: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 0 0 8px 8px;
}

/* 滚动条美化 */
.tool-list::-webkit-scrollbar,
.modal-body::-webkit-scrollbar,
textarea::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.tool-list::-webkit-scrollbar-track,
.modal-body::-webkit-scrollbar-track,
textarea::-webkit-scrollbar-track {
  background: transparent;
}
.tool-list::-webkit-scrollbar-thumb,
.modal-body::-webkit-scrollbar-thumb,
textarea::-webkit-scrollbar-thumb {
  background: rgba(0, 255, 200, 0.2);
  border-radius: 3px;
}
.tool-list::-webkit-scrollbar-thumb:hover,
.modal-body::-webkit-scrollbar-thumb:hover,
textarea::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 255, 200, 0.4);
}
</style>
