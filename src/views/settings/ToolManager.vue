<template>
  <div class="tool-manager">
    <div class="header">
      <div class="section-title">MCP TOOLS / 工具管理</div>
      <button class="btn-primary" @click="debouncedOpenCreateModal">
        + 新增工具
      </button>
    </div>

    <div class="tool-list">
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="tools.length === 0" class="empty">暂无工具</div>
      
      <div v-else class="tool-card" v-for="tool in tools" :key="tool.id">
        <div class="card-header">
          <span class="tool-name">{{ tool.name }}</span>
          <div class="badges">
            <span class="tool-version" v-if="tool.version">v{{ tool.version }}</span>
            <span class="badge sensitivity" :class="(tool.sensitivity || 'LOW').toLowerCase()">
              {{ tool.sensitivity || 'LOW' }}
            </span>
            <span v-if="tool.requiresApproval" class="badge approval">需审批</span>
          </div>
        </div>
        <div class="card-body">
          <p class="desc">{{ tool.description }}</p>
          <div class="meta">
            <span class="tag">Bean: {{ tool.beanName }}</span>
            <span class="tag">Method: {{ tool.methodName }}</span>
          </div>
        </div>
        <div class="card-footer">
          <button class="btn-text" @click="debouncedOpenEditModal(tool)">编辑</button>
          <button class="btn-text delete" @click="debouncedHandleDelete(tool)">删除</button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showModal" class="mcp-modal-wrapper">
        <div 
          class="modal"
          :style="{ left: modalX + 'px', top: modalY + 'px' }"
          @mouseenter="$emit('mouseenter')"
          @mouseleave="$emit('mouseleave')"
        >
          <div class="modal-header" @mousedown="startDrag">
            <h3>{{ isEdit ? '编辑工具' : '注册新工具' }}</h3>
            <div class="header-actions">
              <button v-if="!isEdit" class="btn-import" @click="debouncedTriggerFileUpload" title="从 JSON 导入" :disabled="isSaving">
                <span>📂</span> 导入配置
              </button>
              <button class="close-btn" @click="debouncedCloseModal" :disabled="isSaving">×</button>
            </div>
          </div>
          
          <div class="modal-body">
            <input type="file" ref="fileInput" accept=".json" style="display: none" @change="handleFileUpload" />

            <div class="form-group">
              <label>工具名称 (Name)*</label>
              <input v-model="form.name" placeholder="例如: web_search" :disabled="isSaving" />
            </div>

            <div class="form-group">
              <label>工具描述 (Description)*</label>
              <textarea v-model="form.description" placeholder="详细说明该工具的用途，供大模型理解..." :disabled="isSaving"></textarea>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label>Spring Bean 名称 (Bean Name)*</label>
                <input v-model="form.beanName" placeholder="例如: searchTools" :disabled="isSaving" />
              </div>
              <div class="form-group">
                <label>方法名称 (Method Name)*</label>
                <input v-model="form.methodName" placeholder="例如: executeSearch" :disabled="isSaving" />
              </div>
            </div>

            <div class="form-group">
              <label>输入参数结构 (Input Schema JSON)*</label>
              <textarea 
                v-model="form.inputSchema" 
                class="code-editor" 
                placeholder='{"type":"object", "properties": {}}'
                :disabled="isSaving"
              ></textarea>
            </div>

            <div class="form-group">
              <label>输出参数结构 (Output Schema JSON)</label>
              <textarea 
                v-model="form.outputSchema" 
                class="code-editor" 
                placeholder='{"type":"string"}'
                :disabled="isSaving"
              ></textarea>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label>版本号 (Version)</label>
                <input v-model="form.version" placeholder="例如: 1.0.0" :disabled="isSaving" />
              </div>
              <div class="form-group">
                <label>敏感度 (Sensitivity)</label>
                <select v-model="form.sensitivity" :disabled="isSaving">
                  <option value="LOW">低 (LOW)</option>
                  <option value="MEDIUM">中 (MEDIUM)</option>
                  <option value="HIGH">高 (HIGH)</option>
                </select>
              </div>
            </div>

            <div class="form-group checkbox-group">
              <label class="checkbox-label">
                <input type="checkbox" v-model="form.requiresApproval" :disabled="isSaving" />
                <span>执行前需要审批 (Requires Approval)</span>
              </label>
            </div>
          </div>

          <div class="modal-actions">
            <button class="btn-secondary" @click="debouncedCloseModal" :disabled="isSaving">取消</button>
            <button class="btn-primary" @click="debouncedHandleSave" :disabled="isSaving">
              <span v-if="isSaving" class="spinner"></span>
              {{ isSaving ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive, onBeforeUnmount } from 'vue';

const emit = defineEmits(['mouseenter', 'mouseleave']);

const tools = ref([]);
const loading = ref(false);
const showModal = ref(false);
const isEdit = ref(false);
const isSaving = ref(false);
const fileInput = ref(null);

function debounce(func, wait) {
  let timeout;
  return function(...args) {
    const context = this;
    clearTimeout(timeout);
    timeout = setTimeout(() => func.apply(context, args), wait);
  };
}

const modalX = ref(window.innerWidth / 2 - 250);
const modalY = ref(window.innerHeight / 2 - 300);
let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(e) {
  if (e.target.closest('.close-btn') || e.target.closest('.btn-import')) return;
  isDragging = true;
  dragOffset.x = e.clientX - modalX.value;
  dragOffset.y = e.clientY - modalY.value;
  window.addEventListener('mousemove', onDrag);
  window.addEventListener('mouseup', stopDrag);
}

function onDrag(e) {
  if (!isDragging) return;
  const maxX = window.innerWidth - 120;
  const maxY = window.innerHeight - 80;
  modalX.value = Math.min(Math.max(e.clientX - dragOffset.x, -360), maxX);
  modalY.value = Math.min(Math.max(e.clientY - dragOffset.y, -420), maxY);
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener('mousemove', onDrag);
  window.removeEventListener('mouseup', stopDrag);
}

const form = reactive({
  id: '',
  name: '',
  description: '',
  beanName: '',
  methodName: '',
  inputSchema: '',
  outputSchema: '',
  version: '',
  owner: '',
  requiresApproval: false,
  sensitivity: 'LOW'
});

onMounted(() => {
  fetchTools();
});

onBeforeUnmount(() => {
  stopDrag();
});

async function fetchTools() {
  loading.value = true;
  try {
    const resources = await window.mcpApi.listResources();
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
  modalX.value = window.innerWidth / 2 - 250;
  modalY.value = window.innerHeight / 2 - 300;
  showModal.value = true;
}
const debouncedOpenCreateModal = debounce(openCreateModal, 300);

function openEditModal(tool) {
  isEdit.value = true;
  Object.assign(form, tool);
  
  if (typeof form.inputSchema === 'object') {
    form.inputSchema = JSON.stringify(form.inputSchema, null, 2);
  }
  if (typeof form.outputSchema === 'object') {
    form.outputSchema = JSON.stringify(form.outputSchema, null, 2);
  }
  
  modalX.value = window.innerWidth / 2 - 250;
  modalY.value = window.innerHeight / 2 - 300;
  showModal.value = true;
}
const debouncedOpenEditModal = debounce(openEditModal, 300);

function closeModal() {
  if (isSaving.value) return;
  showModal.value = false;
}
const debouncedCloseModal = debounce(closeModal, 300);

function resetForm() {
  form.id = '';
  form.name = '';
  form.description = '';
  form.beanName = '';
  form.methodName = '';
  form.inputSchema = '{\n  "type": "object",\n  "properties": {}\n}';
  form.outputSchema = '';
  form.version = '';
  form.owner = '';
  form.requiresApproval = false;
  form.sensitivity = 'LOW';
}

function triggerFileUpload() {
  if (isSaving.value) return;
  fileInput.value?.click();
}
const debouncedTriggerFileUpload = debounce(triggerFileUpload, 300);

function handleFileUpload(event) {
  const file = event.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = (e) => {
    try {
      const json = JSON.parse(e.target.result);
      if (json.name) form.name = json.name;
      if (json.description) form.description = json.description;
      if (json.beanName) form.beanName = json.beanName;
      if (json.methodName) form.methodName = json.methodName;
      if (json.version) form.version = json.version;
      if (json.owner) form.owner = json.owner;
      if (json.requiresApproval !== undefined) form.requiresApproval = json.requiresApproval;
      if (json.sensitivity) form.sensitivity = json.sensitivity;
      
      if (json.inputSchema) {
        form.inputSchema = typeof json.inputSchema === 'object' 
          ? JSON.stringify(json.inputSchema, null, 2) 
          : json.inputSchema;
      }
      
      if (json.outputSchema) {
        form.outputSchema = typeof json.outputSchema === 'object' 
          ? JSON.stringify(json.outputSchema, null, 2) 
          : json.outputSchema;
      }
      
      alert("JSON 解析成功，表单已自动填充");
    } catch (err) {
      console.error("JSON parse error:", err);
      alert("JSON 解析失败: " + err.message);
    } finally {
      event.target.value = '';
    }
  };
  reader.readAsText(file);
}

async function handleSave() {
  if (!form.name || !form.beanName || !form.methodName) {
    alert("请填写必填字段");
    return;
  }

  try {
    JSON.parse(form.inputSchema);
  } catch (e) {
    alert("Input Schema 必须是有效的 JSON 格式");
    return;
  }

  if (form.outputSchema) {
    try {
      JSON.parse(form.outputSchema);
    } catch (e) {
      alert("Output Schema 必须是有效的 JSON 格式");
      return;
    }
  }

  isSaving.value = true;
  try {
    if (isEdit.value) {
      await window.mcpApi.updateTool({ ...form });
      alert("工具更新成功！");
    } else {
      await window.mcpApi.createTool({ ...form });
      alert("工具创建成功！");
    }
    closeModal();
    fetchTools();
  } catch (err) {
    console.error("Save failed:", err);
    alert("保存失败: " + err.message);
  } finally {
    isSaving.value = false;
  }
}
const debouncedHandleSave = debounce(handleSave, 300);

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
const debouncedHandleDelete = debounce(handleDelete, 300);
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

.badges {
  display: flex;
  gap: 6px;
  align-items: center;
}

.tool-version {
  font-size: 0.8em;
  background: rgba(0, 0, 0, 0.3);
  padding: 2px 6px;
  border-radius: 4px;
}

.badge {
  font-size: 0.75em;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: bold;
}
.badge.approval { background: rgba(255, 150, 0, 0.2); color: #fbd38d; }
.badge.sensitivity.low { background: rgba(0, 255, 0, 0.1); color: #9ae6b4; }
.badge.sensitivity.medium { background: rgba(255, 200, 0, 0.2); color: #f6e05e; }
.badge.sensitivity.high { background: rgba(255, 0, 0, 0.2); color: #fc8181; }

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

.btn-primary {
  background: var(--primary, #319795);
  color: #000;
  border: none;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
}
.btn-primary:hover:not(:disabled) { filter: brightness(1.1); }
.btn-primary:disabled { opacity: 0.7; cursor: not-allowed; }

.btn-secondary {
  background: #4a5568;
  color: white;
  border: none;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-text {
  background: none;
  border: none;
  color: #63b3ed;
  cursor: pointer;
  font-size: 0.9em;
}
.btn-text:hover { text-decoration: underline; }
.btn-text.delete { color: #fc8181; }

.mcp-modal-wrapper {
  position: fixed;
  inset: 0;
  z-index: 10000;
  pointer-events: none;
}

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
  pointer-events: auto;
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

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.btn-import {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid var(--primary, #4fd1c5);
  color: var(--primary, #4fd1c5);
  padding: 4px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
  font-weight: bold;
}
.btn-import:hover:not(:disabled) {
  background: var(--primary, #4fd1c5);
  color: #000;
}
.btn-import:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
.close-btn:hover:not(:disabled) { color: #fff; }

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

input, textarea, select {
  width: 100%;
  background: rgba(0,0,0,0.3);
  border: 1px solid var(--border, #4a5568);
  color: white;
  padding: 8px;
  border-radius: 4px;
  font-family: inherit;
  box-sizing: border-box;
}

input:focus, textarea:focus, select:focus {
  outline: none;
  border-color: var(--primary, #4fd1c5);
}
input:disabled, textarea:disabled, select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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

.checkbox-group {
  display: flex;
  align-items: center;
  margin-top: 10px;
}
.checkbox-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--text-main);
}
.checkbox-label input {
  width: auto;
  cursor: pointer;
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

.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(0,0,0,0.3);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-right: 6px;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

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
  background: var(--border);
  border-radius: 3px;
}
.tool-list::-webkit-scrollbar-thumb:hover,
.modal-body::-webkit-scrollbar-thumb:hover,
textarea::-webkit-scrollbar-thumb:hover {
  background: var(--primary);
}
</style>
