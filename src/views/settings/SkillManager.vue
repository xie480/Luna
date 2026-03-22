<template>
  <div class="skill-manager">
    <div class="header">
      <div class="section-title">MCP SKILLS / 技能管理</div>
      <button class="btn-primary" @click="debouncedOpenCreateModal">
        + 新增技能
      </button>
    </div>

    <!-- 技能列表 -->
    <div class="skill-list">
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="skills.length === 0" class="empty">暂无技能</div>
      
      <div v-else class="skill-card" v-for="skill in skills" :key="skill.id">
        <div class="card-header">
          <span class="skill-name">{{ skill.name }}</span>
          <div class="badges">
            <span class="skill-version" v-if="skill.version">v{{ skill.version }}</span>
            <span class="badge mode">{{ skill.runMode || 'SYNC' }}</span>
          </div>
        </div>
        <div class="card-body">
          <p class="desc">{{ skill.description }}</p>
          <div class="meta">
            <span class="tag">Bean: {{ skill.beanName || '-' }}</span>
            <span class="tag">Method: {{ skill.methodName || '-' }}</span>
            <span class="tag">Owner: {{ skill.owner || '-' }}</span>
          </div>
          <div class="meta" v-if="Array.isArray(skill.requiredCapabilities) && skill.requiredCapabilities.length">
            <span class="tag">Capabilities: {{ skill.requiredCapabilities.join(', ') }}</span>
          </div>
        </div>
        <div class="card-footer">
          <button class="btn-text" @click="debouncedOpenEditModal(skill)">编辑</button>
          <button class="btn-text delete" @click="debouncedHandleDelete(skill)">删除</button>
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
            <h3>{{ isEdit ? '编辑技能' : '注册新技能' }}</h3>
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
              <label>技能名称 (Name)*</label>
              <input v-model="form.name" placeholder="例如: skill_search_ingest_kb" :disabled="isSaving" />
            </div>

            <div class="form-group">
              <label>技能描述 (Description)*</label>
              <textarea v-model="form.description" placeholder="详细说明该技能的用途，供大模型理解..." :disabled="isSaving"></textarea>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label>版本号 (Version)</label>
                <input v-model="form.version" placeholder="例如: 1.0.0" :disabled="isSaving" />
              </div>
              <div class="form-group">
                <label>负责人 (Owner)</label>
                <input v-model="form.owner" placeholder="例如: luna-team" :disabled="isSaving" />
              </div>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label>Spring Bean 名称 (Bean Name)*</label>
                <input v-model="form.beanName" placeholder="例如: skillExecutor" :disabled="isSaving" />
              </div>
              <div class="form-group">
                <label>方法名称 (Method Name)*</label>
                <input v-model="form.methodName" placeholder="例如: execute" :disabled="isSaving" />
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
                placeholder='{"type":"object"}'
                :disabled="isSaving"
              ></textarea>
            </div>

            <div class="form-group">
              <label>执行模式 (Run Mode)</label>
              <select v-model="form.runMode" :disabled="isSaving">
                <option value="SYNC">同步 (SYNC)</option>
                <option value="ASYNC">异步 (ASYNC)</option>
              </select>
            </div>

            <div class="form-group">
              <label>所需能力 (requiredCapabilities)</label>
              <textarea
                v-model="form.requiredCapabilitiesText"
                placeholder='支持两种格式：&#10;1) JSON数组：["WEB_SEARCH","KB_INSERT"]&#10;2) 逗号分隔：WEB_SEARCH, KB_INSERT'
                :disabled="isSaving"
              ></textarea>
            </div>

            <div class="form-group">
              <label>能力槽位 (toolSlots JSON)</label>
              <textarea
                v-model="form.toolSlotsText"
                class="code-editor"
                placeholder='[{"slot":"search","capability":"WEB_SEARCH","required":true}]'
                :disabled="isSaving"
              ></textarea>
            </div>

            <div class="form-group">
              <label>思维链 (thoughtChain)</label>
              <textarea
                v-model="form.thoughtChainText"
                placeholder='支持两种格式：&#10;1) JSON数组：["先搜索","再抓取"]&#10;2) 每行一条'
                :disabled="isSaving"
              ></textarea>
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
import { ref, onMounted, reactive } from 'vue';

const emit = defineEmits(['mouseenter', 'mouseleave']);

const skills = ref([]);
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

const modalX = ref(window.innerWidth / 2 - 260);
const modalY = ref(window.innerHeight / 2 - 320);
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
  modalX.value = e.clientX - dragOffset.x;
  modalY.value = e.clientY - dragOffset.y;
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
  version: '',
  owner: '',
  beanName: '',
  methodName: '',
  inputSchema: '',
  outputSchema: '',
  runMode: 'SYNC',
  requiredCapabilitiesText: '',
  toolSlotsText: '',
  thoughtChainText: ''
});

onMounted(() => {
  fetchSkills();
});

async function fetchSkills() {
  loading.value = true;
  try {
    let list = [];
    if (window.mcpApi?.listSkills) {
      list = await window.mcpApi.listSkills();
    } else {
      const resources = await window.mcpApi.listResources();
      list = (resources || []).filter(r => r.type === 'SKILL');
    }

    skills.value = (Array.isArray(list) ? list : []).sort((a, b) => {
      const an = String(a?.name || '');
      const bn = String(b?.name || '');
      return an.localeCompare(bn);
    });
  } catch (err) {
    console.error("Failed to fetch skills:", err);
    alert("获取技能列表失败: " + (err?.message || String(err)));
  } finally {
    loading.value = false;
  }
}

function openCreateModal() {
  isEdit.value = false;
  resetForm();
  modalX.value = window.innerWidth / 2 - 260;
  modalY.value = window.innerHeight / 2 - 320;
  showModal.value = true;
}
const debouncedOpenCreateModal = debounce(openCreateModal, 300);

function stringifyMaybeJson(value, fallback = '') {
  if (value === null || value === undefined || value === '') return fallback;
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}

function normalizeStringArrayText(value) {
  if (!value) return '';
  if (Array.isArray(value)) return value.join('\n');
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) return parsed.join('\n');
      return value;
    } catch {
      return value;
    }
  }
  return '';
}

function openEditModal(skill) {
  isEdit.value = true;

  form.id = skill?.id ?? '';
  form.name = skill?.name ?? '';
  form.description = skill?.description ?? '';
  form.version = skill?.version ?? '';
  form.owner = skill?.owner ?? '';
  form.beanName = skill?.beanName ?? '';
  form.methodName = skill?.methodName ?? '';
  form.inputSchema = stringifyMaybeJson(skill?.inputSchema, '{\n  "type": "object",\n  "properties": {}\n}');
  form.outputSchema = stringifyMaybeJson(skill?.outputSchema, '');
  form.runMode = (skill?.runMode === 'ASYNC' ? 'ASYNC' : 'SYNC');
  form.requiredCapabilitiesText = normalizeStringArrayText(skill?.requiredCapabilities);
  form.toolSlotsText = stringifyMaybeJson(skill?.toolSlots, '');
  form.thoughtChainText = normalizeStringArrayText(skill?.thoughtChain);

  modalX.value = window.innerWidth / 2 - 260;
  modalY.value = window.innerHeight / 2 - 320;
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
  form.version = '1.0.0';
  form.owner = '';
  form.beanName = '';
  form.methodName = '';
  form.inputSchema = '{\n  "type": "object",\n  "properties": {}\n}';
  form.outputSchema = '{\n  "type": "object"\n}';
  form.runMode = 'SYNC';
  form.requiredCapabilitiesText = '';
  form.toolSlotsText = '';
  form.thoughtChainText = '';
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

      if (json.id !== undefined) form.id = json.id;
      if (json.name) form.name = json.name;
      if (json.description) form.description = json.description;
      if (json.version) form.version = json.version;
      if (json.owner) form.owner = json.owner;
      if (json.beanName) form.beanName = json.beanName;
      if (json.methodName) form.methodName = json.methodName;
      if (json.runMode) form.runMode = json.runMode;

      if (json.inputSchema !== undefined) form.inputSchema = stringifyMaybeJson(json.inputSchema, form.inputSchema);
      if (json.outputSchema !== undefined) form.outputSchema = stringifyMaybeJson(json.outputSchema, form.outputSchema);

      if (json.requiredCapabilities !== undefined) {
        form.requiredCapabilitiesText = normalizeStringArrayText(json.requiredCapabilities);
      }
      if (json.toolSlots !== undefined) {
        form.toolSlotsText = stringifyMaybeJson(json.toolSlots, '');
      }
      if (json.thoughtChain !== undefined) {
        form.thoughtChainText = normalizeStringArrayText(json.thoughtChain);
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

function parseStringArray(text) {
  const raw = String(text || '').trim();
  if (!raw) return [];

  if (raw.startsWith('[')) {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      throw new Error("必须是数组");
    }
    return parsed.map(v => String(v).trim()).filter(Boolean);
  }

  return raw
    .split(/[\n,]/g)
    .map(s => s.trim())
    .filter(Boolean);
}

function parseToolSlots(text) {
  const raw = String(text || '').trim();
  if (!raw) return [];

  const parsed = JSON.parse(raw);
  if (!Array.isArray(parsed)) {
    throw new Error("toolSlots 必须是 JSON 数组");
  }

  parsed.forEach((slot, idx) => {
    if (!slot || typeof slot !== 'object') {
      throw new Error(`toolSlots[${idx}] 必须是对象`);
    }
    if (!slot.slot || !slot.capability) {
      throw new Error(`toolSlots[${idx}] 缺少 slot 或 capability`);
    }
    if (slot.required !== undefined && typeof slot.required !== 'boolean') {
      throw new Error(`toolSlots[${idx}].required 必须是布尔值`);
    }
  });

  return parsed;
}

function toJsonString(rawText, fieldName) {
  const text = String(rawText || '').trim();
  if (!text) return '';
  try {
    const parsed = JSON.parse(text);
    return JSON.stringify(parsed);
  } catch (e) {
    throw new Error(`${fieldName} 必须是有效的 JSON 格式`);
  }
}

function buildPayload() {
  if (!form.name || !form.beanName || !form.methodName) {
    throw new Error("请填写必填字段：Name / Bean Name / Method Name");
  }

  if (!['SYNC', 'ASYNC'].includes(form.runMode)) {
    throw new Error("Run Mode 只能是 SYNC 或 ASYNC");
  }

  const inputSchema = toJsonString(form.inputSchema, "Input Schema");
  if (!inputSchema) {
    throw new Error("Input Schema 不能为空");
  }

  const outputSchema = toJsonString(form.outputSchema, "Output Schema");
  const requiredCapabilities = parseStringArray(form.requiredCapabilitiesText);
  const thoughtChain = parseStringArray(form.thoughtChainText);
  const toolSlots = parseToolSlots(form.toolSlotsText);

  const payload = {
    name: String(form.name).trim(),
    description: String(form.description || '').trim(),
    version: String(form.version || '').trim() || undefined,
    owner: String(form.owner || '').trim() || undefined,
    beanName: String(form.beanName).trim(),
    methodName: String(form.methodName).trim(),
    inputSchema,
    outputSchema: outputSchema || undefined,
    runMode: form.runMode,
    requiredCapabilities,
    toolSlots,
    thoughtChain
  };

  if (isEdit.value) {
    if (!form.id && form.id !== 0) {
      throw new Error("更新技能时必须包含 id");
    }
    payload.id = form.id;
  }

  Object.keys(payload).forEach((k) => {
    if (payload[k] === undefined) delete payload[k];
  });

  return payload;
}

async function handleSave() {
  isSaving.value = true;
  try {
    const payload = buildPayload();

    if (isEdit.value) {
      await window.mcpApi.updateSkill(payload);
      alert("技能更新成功！");
    } else {
      await window.mcpApi.createSkill(payload);
      alert("技能创建成功！");
    }

    closeModal();
    fetchSkills();
  } catch (err) {
    console.error("Save failed:", err);
    alert("保存失败: " + (err?.message || String(err)));
  } finally {
    isSaving.value = false;
  }
}
const debouncedHandleSave = debounce(handleSave, 300);

async function handleDelete(skill) {
  if (!confirm(`确定要删除技能 "${skill.name}" 吗？`)) return;
  
  try {
    await window.mcpApi.deleteSkill(skill.id);
    fetchSkills();
  } catch (err) {
    console.error("Delete failed:", err);
    alert("删除失败: " + (err?.message || String(err)));
  }
}
const debouncedHandleDelete = debounce(handleDelete, 300);
</script>

<style scoped>
.skill-manager {
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

.skill-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 15px;
  overflow-y: auto;
  padding-right: 5px;
}

.skill-card {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  border-radius: 6px;
  padding: 15px;
  transition: transform 0.2s;
}

.skill-card:hover {
  transform: translateY(-2px);
  background: rgba(255, 255, 255, 0.08);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.skill-name {
  font-weight: bold;
  font-size: 1.1em;
  color: var(--primary, #4fd1c5);
}

.badges {
  display: flex;
  gap: 6px;
  align-items: center;
}

.skill-version {
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
.badge.mode { background: rgba(0, 150, 255, 0.2); color: #63b3ed; }

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
  margin-bottom: 10px;
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

/* Modal Wrapper */
.mcp-modal-wrapper {
  position: fixed;
  inset: 0;
  z-index: 10000;
  pointer-events: none;
}

/* Draggable Modal */
.modal {
  position: fixed;
  background: var(--bg-panel, #1a202c);
  border-radius: 8px;
  width: 520px;
  max-height: 88vh;
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

.modal-actions {
  padding: 15px 20px;
  border-top: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 0 0 8px 8px;
}

/* 加载动画 Spinner */
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

.skill-list::-webkit-scrollbar,
.modal-body::-webkit-scrollbar,
textarea::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.skill-list::-webkit-scrollbar-track,
.modal-body::-webkit-scrollbar-track,
textarea::-webkit-scrollbar-track {
  background: transparent;
}
.skill-list::-webkit-scrollbar-thumb,
.modal-body::-webkit-scrollbar-thumb,
textarea::-webkit-scrollbar-thumb {
  background: var(--border);
  border-radius: 3px;
}
.skill-list::-webkit-scrollbar-thumb:hover,
.modal-body::-webkit-scrollbar-thumb:hover,
textarea::-webkit-scrollbar-thumb:hover {
  background: var(--primary);
}
</style>
