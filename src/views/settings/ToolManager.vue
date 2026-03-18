<template>
  <div class="tool-manager">
    <div class="header">
      <h2>MCP 工具管理</h2>
      <button class="btn-primary" @click="openCreateModal">
        + 新增工具
      </button>
    </div>

    <!-- 工具列表 -->
    <div class="tool-list">
      <div v-if="loading" class="loading">加載中...</div>
      <div v-else-if="tools.length === 0" class="empty">暫無工具</div>
      
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
          <button class="btn-text" @click="openEditModal(tool)">編輯</button>
          <button class="btn-text delete" @click="handleDelete(tool)">刪除</button>
        </div>
      </div>
    </div>

    <!-- 編輯/新增 彈窗 -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal">
        <h3>{{ isEdit ? '編輯工具' : '註冊新工具' }}</h3>
        
        <div class="form-group">
          <label>工具名稱 (Name)*</label>
          <input v-model="form.name" placeholder="例如: web_search" />
        </div>

        <div class="form-group">
          <label>描述 (Description)*</label>
          <textarea v-model="form.description" placeholder="詳細說明用途..."></textarea>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label>Bean Name*</label>
            <input v-model="form.beanName" placeholder="Spring Bean Name" />
          </div>
          <div class="form-group">
            <label>Method Name*</label>
            <input v-model="form.methodName" placeholder="Method Name" />
          </div>
        </div>

        <div class="form-group">
          <label>Input Schema (JSON)*</label>
          <textarea 
            v-model="form.inputSchema" 
            class="code-editor" 
            placeholder='{"type":"object"...}'
          ></textarea>
        </div>

        <div class="form-group">
          <label>版本 (Version)</label>
          <input v-model="form.version" placeholder="1.0.0" />
        </div>

        <div class="modal-actions">
          <button class="btn-secondary" @click="closeModal">取消</button>
          <button class="btn-primary" @click="handleSave">保存</button>
        </div>
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

// 表單數據
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
    // 調用後端獲取所有資源
    const resources = await window.mcpApi.listResources();
    // 過濾出類型為 TOOL 的資源
    tools.value = resources.filter(r => r.type === 'TOOL');
  } catch (err) {
    console.error("Failed to fetch tools:", err);
    alert("獲取工具列表失敗: " + err.message);
  } finally {
    loading.value = false;
  }
}

function openCreateModal() {
  isEdit.value = false;
  resetForm();
  showModal.value = true;
}

function openEditModal(tool) {
  isEdit.value = true;
  Object.assign(form, tool);
  // 確保 inputSchema 是字符串顯示
  if (typeof form.inputSchema === 'object') {
    form.inputSchema = JSON.stringify(form.inputSchema, null, 2);
  }
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
    alert("請填寫必填字段");
    return;
  }

  // 驗證 JSON 格式
  try {
    JSON.parse(form.inputSchema);
  } catch (e) {
    alert("Input Schema 必須是有效的 JSON 格式");
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
    alert("保存失敗: " + err.message);
  }
}

async function handleDelete(tool) {
  if (!confirm(`確定要刪除工具 "${tool.name}" 嗎？`)) return;
  
  try {
    await window.mcpApi.deleteTool(tool.id);
    fetchTools();
  } catch (err) {
    console.error("Delete failed:", err);
    alert("刪除失敗: " + err.message);
  }
}
</script>

<style scoped>
.tool-manager {
  padding: 20px;
  color: #eee;
  height: 100%;
  overflow-y: auto;
  background: rgba(0, 0, 0, 0.4);
  border-radius: 8px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  padding-bottom: 10px;
}

.tool-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 15px;
}

.tool-card {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
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
  color: #4fd1c5;
}

.tool-version {
  font-size: 0.8em;
  background: rgba(0, 0, 0, 0.3);
  padding: 2px 6px;
  border-radius: 4px;
}

.desc {
  font-size: 0.9em;
  color: #aaa;
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
  background: rgba(79, 209, 197, 0.1);
  color: #81e6d9;
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
  background: #319795;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
}
.btn-primary:hover { background: #2c7a7b; }

.btn-secondary {
  background: #4a5568;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
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

/* Modal */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal {
  background: #1a202c;
  padding: 25px;
  border-radius: 8px;
  width: 500px;
  max-width: 90%;
  max-height: 90vh;
  overflow-y: auto;
  border: 1px solid #2d3748;
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
  color: #cbd5e0;
}

input, textarea {
  width: 100%;
  background: #2d3748;
  border: 1px solid #4a5568;
  color: white;
  padding: 8px;
  border-radius: 4px;
  font-family: inherit;
}

input:focus, textarea:focus {
  outline: none;
  border-color: #4fd1c5;
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
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
</style>
