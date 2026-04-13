<template>
  <FloatingPanelShell
    panelClass="capability-center-panel"
    title="MCP CAPABILITY CENTER"
    subtitle="Resource Discovery / Directory / RPC Console"
    :initial-x="initialX"
    :initial-y="initialY"
    :initial-width="1240"
    :initial-height="760"
    :min-width="980"
    :min-height="620"
    @close="$emit('close')"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="toolbar">
      <input v-model="searchQuery" class="field" placeholder="搜索能力资源或当前目录项" @keyup.enter="performSearch" />
      <select v-model="serverCode" class="field" @change="refreshCurrentView">
        <option value="">全部 / 默认服务</option>
        <option v-for="item in serverOptions" :key="item" :value="item">{{ item }}</option>
      </select>
      <div class="toolbar-actions">
        <button class="btn-secondary" @click="refreshCurrentView">{{ loading ? "加载中..." : "刷新" }}</button>
        <button class="btn-secondary" @click="handleSyncCatalog">{{ actionLoading ? "同步中..." : "同步目录" }}</button>
        <button class="btn-secondary" :class="{ active: listMode === 'card' }" @click="listMode = 'card'">卡片</button>
        <button class="btn-secondary" :class="{ active: listMode === 'row' }" @click="listMode = 'row'">列表</button>
        <button class="btn-secondary" @click="setActiveView('rpc')">JSON-RPC</button>
      </div>
    </div>

    <transition name="toast-fade">
      <div v-if="toast.text" class="toolbar-toast" :class="toast.type">{{ toast.text }}</div>
    </transition>

    <div class="layout-grid">
      <aside class="nav-pane">
        <button v-for="item in navItems" :key="item.id" class="nav-item" :class="{ active: activeView === item.id }" @click="setActiveView(item.id)">
          <span>{{ item.label }}</span>
          <small>{{ item.desc }}</small>
        </button>
      </aside>

      <section class="list-pane">
        <div class="section-head">
          <div>
            <strong>{{ currentViewMeta.label }}</strong>
            <small>{{ currentViewMeta.desc }}</small>
          </div>
          <span class="muted">{{ displayList.length }} 条</span>
        </div>

        <div v-if="loading" class="empty-state">目录加载中...</div>
        <div v-else-if="!displayList.length" class="empty-state">{{ emptyText }}</div>
        <div v-else-if="listMode === 'card'" class="card-list">
          <button v-for="item in displayList" :key="getItemKey(item)" class="resource-card" :class="{ selected: getItemKey(item) === selectedKey }" @click="handleSelect(item)">
            <div class="row between">
              <strong>{{ getItemTitle(item) }}</strong>
              <StatusBadge :label="getItemType(item)" :tone="item.sensitivity || item.type" />
            </div>
            <div class="row muted">
              <span>{{ item.serverCode || item.resourceUri || "-" }}</span>
              <span>{{ item.version ? `v${item.version}` : "-" }}</span>
            </div>
            <p class="desc">{{ item.description || item.title || "暂无描述" }}</p>
            <div class="row wrap">
              <StatusBadge v-if="item.requiresApproval" label="需审批" tone="WARNING" />
              <StatusBadge v-if="item.sensitivity" :label="item.sensitivity" tone="WARNING" />
              <StatusBadge v-if="item.runMode" :label="item.runMode" tone="RUNNING" />
            </div>
          </button>
        </div>
        <div v-else class="row-list">
          <button v-for="item in displayList" :key="getItemKey(item)" class="row-card" :class="{ selected: getItemKey(item) === selectedKey }" @click="handleSelect(item)">
            <div class="row-card-main">
              <strong>{{ getItemTitle(item) }}</strong>
              <span>{{ item.description || "暂无描述" }}</span>
            </div>
            <div class="row-card-meta">
              <StatusBadge :label="getItemType(item)" :tone="item.sensitivity || item.type" />
              <span>{{ item.serverCode || "-" }}</span>
            </div>
          </button>
        </div>
      </section>

      <section class="detail-pane">
        <div class="section-head">
          <div>
            <strong>{{ activeView === 'rpc' ? '协议调试' : selectedDetail ? getItemTitle(selectedDetail) : '详情与调试' }}</strong>
            <small>{{ activeView === 'rpc' ? '直接构造 /mcp/rpc 请求体' : selectedDetail ? `${getItemType(selectedDetail)} · ${selectedDetail.serverCode || '未指定服务'}` : 'Schema / 原始 JSON / 调试结果' }}</small>
          </div>
        </div>

        <div class="detail-scroll">
          <div v-if="selectedDetail" class="panel-card">
            <div class="detail-row"><label>名称</label><span>{{ getItemTitle(selectedDetail) }}</span></div>
            <div class="detail-row"><label>类型</label><span>{{ getItemType(selectedDetail) }}</span></div>
            <div class="detail-row"><label>服务</label><span>{{ selectedDetail.serverCode || "-" }}</span></div>
            <div class="detail-row"><label>版本</label><span>{{ selectedDetail.version || "-" }}</span></div>
            <div class="detail-row"><label>说明</label><span>{{ selectedDetail.description || "-" }}</span></div>
          </div>

          <div v-if="detailMissing" class="empty-state detail-empty warning">
            资源详情不存在或已被移除，当前展示的是列表中的缓存信息。
          </div>

          <SchemaViewer v-if="selectedDetail?.inputSchema" title="Input Schema" :schema="selectedDetail.inputSchema" />
          <SchemaViewer v-if="selectedDetail?.outputSchema" title="Output Schema" :schema="selectedDetail.outputSchema" />
          <SchemaViewer v-if="selectedDetail?.argumentsSchema" title="Arguments Schema" :schema="selectedDetail.argumentsSchema" />

          <div v-if="showToolDebug" class="panel-card">
            <div class="card-title">工具调试</div>
            <div class="two-col">
              <input v-model="toolCallForm.serverCode" class="field" placeholder="serverCode（可选）" />
              <input v-model="toolCallForm.toolName" class="field" placeholder="toolName" />
            </div>
            <textarea v-model="toolCallForm.argumentsJson" class="field code-input" placeholder='argumentsJson，例如 {"query":"hello"}'></textarea>
            <button class="btn-primary" @click="runToolCall">执行工具</button>
          </div>

          <div v-if="showPromptDebug" class="panel-card">
            <div class="card-title">Prompt 试读</div>
            <div class="two-col">
              <input v-model="promptDebugForm.serverCode" class="field" placeholder="serverCode（可选）" />
              <input v-model="promptDebugForm.promptName" class="field" placeholder="promptName" />
            </div>
            <textarea v-model="promptDebugForm.argumentsJson" class="field code-input" placeholder='argumentsJson，例如 {"topic":"luna"}'></textarea>
            <button class="btn-primary" @click="runPromptGet">读取 Prompt</button>
          </div>

          <div v-if="showResourceRead" class="panel-card">
            <div class="card-title">资源读取</div>
            <div class="two-col">
              <input v-model="resourceDebugForm.serverCode" class="field" placeholder="serverCode（可选）" />
              <input v-model="resourceDebugForm.resourceUri" class="field" placeholder="resourceUri" />
            </div>
            <button class="btn-primary" @click="runResourceRead">读取资源</button>
          </div>

          <div v-if="showWriteEditor" class="panel-card">
            <div class="card-title">{{ writeTitle }}</div>
            <textarea v-model="writeEditor" class="field code-input tall" placeholder="按接口文档填写 JSON 载荷"></textarea>
            <button class="btn-primary" @click="saveWritePayload">提交写入</button>
          </div>

          <div v-if="activeView === 'rpc'" class="panel-card">
            <div class="card-title">JSON-RPC 调试</div>
            <textarea v-model="rpcEditor" class="field code-input tall" placeholder='{"jsonrpc":"2.0","id":"debug-1","method":"tools/list","params":{}}'></textarea>
            <button class="btn-primary" @click="runRpc">发送 RPC</button>
          </div>

          <JsonPreviewBlock v-if="debugResult !== null" title="调试结果" :value="debugResult" :max-height="260" />
          <JsonPreviewBlock v-if="writeResult !== null" title="写入结果" :value="writeResult" :max-height="260" />
          <JsonPreviewBlock v-if="selectedDetail" title="原始 JSON" :value="selectedDetail" :max-height="320" />

          <div v-if="!selectedDetail && activeView !== 'rpc'" class="empty-state detail-empty">
            选择左侧资源后，这里会展示详情、Schema 和调试表单。
          </div>
        </div>
      </section>
    </div>
  </FloatingPanelShell>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { callMcpRpc, callMcpTool, getMcpPrompt, getMcpResourceById, listMcpCatalogResources, listMcpPrompts, listMcpResources, listMcpTools, readMcpCatalogResource, saveMcpPromptCatalog, saveMcpResourceCatalog, saveMcpServerRegistry, saveMcpToolCatalog, saveMcpToolImplMapping, saveWorkflowTemplate, searchMcpResources, syncMcpCatalog } from "../api/index.js";
import { ensureArray, normalizeResource, safeJsonParse, stringifyPretty, toJsonPayload, toJsonString } from "../utils/data-utils.js";
import FloatingPanelShell from "./common/FloatingPanelShell.vue";
import JsonPreviewBlock from "./common/JsonPreviewBlock.vue";
import SchemaViewer from "./common/SchemaViewer.vue";
import StatusBadge from "./common/StatusBadge.vue";

defineEmits(["close", "mouseenter", "mouseleave"]);

const initialX = window.innerWidth / 2 - 620;
const initialY = window.innerHeight / 2 - 360;
const navItems = [
  { id: "overview", label: "资源总览", desc: "统一资源视图" },
  { id: "toolCatalog", label: "工具目录", desc: "浏览 / 调试 / 维护" },
  { id: "promptCatalog", label: "Prompt 目录", desc: "浏览 / 试读 / 维护" },
  { id: "resourceCatalog", label: "资源目录", desc: "浏览 / 读取 / 维护" },
  { id: "workflowTemplate", label: "工作流模板", desc: "模板维护" },
  { id: "serverRegistry", label: "服务注册表", desc: "注册信息维护" },
  { id: "implMapping", label: "实现映射", desc: "工具实现绑定" },
  { id: "rpc", label: "JSON-RPC 调试", desc: "协议入口" },
];

const activeView = ref("overview");
const listMode = ref("card");
const loading = ref(false);
const actionLoading = ref(false);
const searchQuery = ref("");
const serverCode = ref("");
const resources = ref([]);
const tools = ref([]);
const prompts = ref([]);
const catalogResources = ref([]);
const selectedDetail = ref(null);
const detailMissing = ref(false);
const selectedKey = ref("");
const debugResult = ref(null);
const writeResult = ref(null);
const rpcEditor = ref('{\n  "jsonrpc": "2.0",\n  "id": "rpc-debug-1",\n  "method": "tools/list",\n  "params": {\n    "serverCode": ""\n  }\n}');
const toolCallForm = reactive({ serverCode: "", toolName: "", argumentsJson: "{\n  \n}" });
const promptDebugForm = reactive({ serverCode: "", promptName: "", argumentsJson: "{\n  \n}" });
const resourceDebugForm = reactive({ serverCode: "", resourceUri: "" });
const writeEditors = reactive({
  toolCatalog: '{\n  "serverCode": "",\n  "toolName": "",\n  "title": "",\n  "description": "",\n  "inputSchema": {},\n  "outputSchema": {},\n  "annotations": {},\n  "tags": [],\n  "version": "",\n  "executionMode": "",\n  "requiresApproval": false,\n  "sensitivity": "",\n  "enabled": true\n}',
  promptCatalog: '{\n  "serverCode": "",\n  "promptName": "",\n  "title": "",\n  "description": "",\n  "argumentsSchema": {},\n  "tags": [],\n  "version": "",\n  "enabled": true\n}',
  resourceCatalog: '{\n  "serverCode": "",\n  "resourceUri": "",\n  "name": "",\n  "description": "",\n  "mimeType": "",\n  "annotations": {},\n  "tags": [],\n  "enabled": true\n}',
  workflowTemplate: '{\n  "workflowName": "",\n  "description": "",\n  "inputSchema": {},\n  "outputSchema": {},\n  "requiredCapabilities": [],\n  "toolSlots": [],\n  "thoughtChain": [],\n  "blueprintJson": {},\n  "version": "",\n  "enabled": true\n}',
  serverRegistry: '{\n  "serverCode": "",\n  "serverName": "",\n  "description": "",\n  "baseUrl": "",\n  "transportType": "HTTP",\n  "authType": "BEARER",\n  "authConfig": {},\n  "enabled": true,\n  "healthStatus": "UNKNOWN"\n}',
  implMapping: '{\n  "serverCode": "",\n  "toolName": "",\n  "implType": "LOCAL_HANDLER",\n  "executionMode": "MCP",\n  "beanName": "",\n  "methodName": "",\n  "routeUri": "",\n  "timeoutMs": 30000,\n  "retryPolicy": {},\n  "enabled": true\n}',
});
const toast = reactive({ text: "", type: "info" });
let toastTimer = null;

const currentViewMeta = computed(() => navItems.find((item) => item.id === activeView.value) || navItems[0]);
const serverOptions = computed(() => Array.from(new Set([...resources.value, ...tools.value, ...prompts.value, ...catalogResources.value].map((item) => item?.serverCode).filter(Boolean))).sort((a, b) => a.localeCompare(b)));
const displayList = computed(() => activeView.value === "overview" ? resources.value : activeView.value === "toolCatalog" || activeView.value === "implMapping" ? tools.value : activeView.value === "promptCatalog" ? prompts.value : activeView.value === "resourceCatalog" ? catalogResources.value : activeView.value === "workflowTemplate" ? resources.value.filter((item) => item.type === "WORKFLOW") : activeView.value === "serverRegistry" ? serverOptions.value.map((code) => ({ id: code, name: code, type: "SERVER", serverCode: code, description: "从当前资源目录中提取的服务编码" })) : []);
const emptyText = computed(() => searchQuery.value ? "暂无匹配资源" : activeView.value === "rpc" ? "使用右侧 RPC 编辑器发送协议请求" : "暂无资源");
const showToolDebug = computed(() => ["toolCatalog", "implMapping"].includes(activeView.value));
const showPromptDebug = computed(() => activeView.value === "promptCatalog");
const showResourceRead = computed(() => activeView.value === "resourceCatalog");
const showWriteEditor = computed(() => ["toolCatalog", "promptCatalog", "resourceCatalog", "workflowTemplate", "serverRegistry", "implMapping"].includes(activeView.value));
const writeTitleMap = { toolCatalog: "工具目录维护", promptCatalog: "Prompt 目录维护", resourceCatalog: "资源目录维护", workflowTemplate: "工作流模板维护", serverRegistry: "服务注册表维护", implMapping: "实现映射维护" };
const writeTitle = computed(() => writeTitleMap[activeView.value] || "目录维护");
const writeEditor = computed({ get: () => writeEditors[activeView.value] || "", set: (value) => { if (writeEditors[activeView.value] !== undefined) writeEditors[activeView.value] = value; } });

function showToast(text, type = "info", duration = 2400) { toast.text = text; toast.type = type; if (toastTimer) clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.text = ""; }, duration); }
function getItemKey(item) { return String(item?.id ?? item?.toolName ?? item?.promptName ?? item?.resourceUri ?? item?.name ?? ""); }
function getItemTitle(item) { return item?.name || item?.title || item?.toolName || item?.promptName || item?.resourceUri || "-"; }
function getItemType(item) { return item?.type || (activeView.value === "toolCatalog" || activeView.value === "implMapping" ? "TOOL" : activeView.value === "promptCatalog" ? "PROMPT" : activeView.value === "resourceCatalog" ? "RESOURCE" : ""); }
function normalizeDescriptor(item, type) { return { ...item, id: item?.id ?? item?.toolName ?? item?.promptName ?? item?.resourceUri ?? item?.name, type, name: item?.name || item?.title || item?.toolName || item?.promptName || item?.resourceUri || "", description: item?.description || "", serverCode: item?.serverCode || "", version: item?.version || "", inputSchema: item?.inputSchema ? stringifyPretty(item.inputSchema, "") : "", outputSchema: item?.outputSchema ? stringifyPretty(item.outputSchema, "") : "", argumentsSchema: item?.argumentsSchema ? stringifyPretty(item.argumentsSchema, "") : "", requiresApproval: !!item?.requiresApproval, sensitivity: item?.sensitivity || "", runMode: item?.runMode || "", resourceUri: item?.resourceUri || "" }; }
function normalizeTagList(value) { return ensureArray(value).map((item) => String(item || "").trim()).filter(Boolean); }
function parseSchemaValue(value) { return safeJsonParse(value, {}); }

async function refreshCurrentView() {
  loading.value = true;
  try {
    if (activeView.value === "overview" || activeView.value === "workflowTemplate") resources.value = ensureArray(await listMcpResources()).map(normalizeResource);
    if (activeView.value === "toolCatalog" || activeView.value === "implMapping") tools.value = ensureArray(await listMcpTools({ serverCode: serverCode.value || undefined })).map((item) => normalizeDescriptor(item, "TOOL"));
    if (activeView.value === "promptCatalog") prompts.value = ensureArray(await listMcpPrompts({ serverCode: serverCode.value || undefined })).map((item) => normalizeDescriptor(item, "PROMPT"));
    if (activeView.value === "resourceCatalog") catalogResources.value = ensureArray(await listMcpCatalogResources({ serverCode: serverCode.value || undefined })).map((item) => normalizeDescriptor(item, "RESOURCE"));
  } catch (error) { console.error("[CapabilityCenter] refresh failed", error); showToast(`目录加载失败：${error?.message || String(error)}`, "error", 3200); } finally { loading.value = false; }
}

async function performSearch() { if (!searchQuery.value.trim()) return refreshCurrentView(); if (activeView.value === "overview") { loading.value = true; try { resources.value = ensureArray(await searchMcpResources({ query: searchQuery.value.trim() })).map(normalizeResource); } catch (error) { showToast(`搜索失败：${error?.message || String(error)}`, "error", 2800); } finally { loading.value = false; } return; } const keyword = searchQuery.value.trim().toLowerCase(); const next = displayList.value.filter((item) => [getItemTitle(item), item.description, item.serverCode, item.resourceUri].filter(Boolean).some((part) => String(part).toLowerCase().includes(keyword))); if (activeView.value === "toolCatalog" || activeView.value === "implMapping") tools.value = next; if (activeView.value === "promptCatalog") prompts.value = next; if (activeView.value === "resourceCatalog") catalogResources.value = next; if (activeView.value === "workflowTemplate") resources.value = next; }

function setActiveView(view) { activeView.value = view; selectedDetail.value = null; detailMissing.value = false; selectedKey.value = ""; debugResult.value = null; writeResult.value = null; refreshCurrentView(); }

async function handleSelect(item) {
  selectedKey.value = getItemKey(item);
  detailMissing.value = false;
  selectedDetail.value = item;

  if (activeView.value === "overview" && item?.id !== undefined && item?.id !== null && item?.id !== "") {
    try {
      const detail = await getMcpResourceById(String(item.id));
      if (!detail || (typeof detail === "object" && Object.keys(detail).length === 0)) {
        detailMissing.value = true;
        selectedDetail.value = normalizeResource(item);
      } else {
        selectedDetail.value = normalizeResource(detail);
      }
    } catch {
      selectedDetail.value = normalizeResource(item);
    }
  }

  if (showToolDebug.value) { toolCallForm.serverCode = item.serverCode || ""; toolCallForm.toolName = item.toolName || item.name || ""; writeEditors.toolCatalog = stringifyPretty({ serverCode: item.serverCode || "", toolName: item.toolName || item.name || "", title: item.title || item.name || "", description: item.description || "", inputSchema: parseSchemaValue(item.inputSchema), outputSchema: parseSchemaValue(item.outputSchema), annotations: safeJsonParse(item.annotations, {}), tags: normalizeTagList(item.tags), version: item.version || "", executionMode: item.executionMode || "", requiresApproval: !!item.requiresApproval, sensitivity: item.sensitivity || "", enabled: item.enabled ?? true }); writeEditors.implMapping = stringifyPretty({ serverCode: item.serverCode || "", toolName: item.toolName || item.name || "", implType: item.implType || "LOCAL_HANDLER", executionMode: item.executionMode || "MCP", beanName: item.beanName || "", methodName: item.methodName || "", routeUri: item.routeUri || "", timeoutMs: Number(item.timeoutMs ?? 30000), retryPolicy: safeJsonParse(item.retryPolicy, {}), enabled: item.enabled ?? true }); }
  if (showPromptDebug.value) { promptDebugForm.serverCode = item.serverCode || ""; promptDebugForm.promptName = item.promptName || item.name || ""; writeEditors.promptCatalog = stringifyPretty({ serverCode: item.serverCode || "", promptName: item.promptName || item.name || "", title: item.title || item.name || "", description: item.description || "", argumentsSchema: parseSchemaValue(item.argumentsSchema), tags: normalizeTagList(item.tags), version: item.version || "", enabled: item.enabled ?? true }); }
  if (showResourceRead.value) { resourceDebugForm.serverCode = item.serverCode || ""; resourceDebugForm.resourceUri = item.resourceUri || item.name || ""; writeEditors.resourceCatalog = stringifyPretty({ serverCode: item.serverCode || "", resourceUri: item.resourceUri || item.name || "", name: item.name || "", description: item.description || "", mimeType: item.mimeType || "", annotations: safeJsonParse(item.annotations, {}), tags: normalizeTagList(item.tags), enabled: item.enabled ?? true }); }
  if (activeView.value === "workflowTemplate") writeEditors.workflowTemplate = stringifyPretty({ workflowName: item.name || "", description: item.description || "", inputSchema: parseSchemaValue(item.inputSchema), outputSchema: parseSchemaValue(item.outputSchema), requiredCapabilities: ensureArray(item.requiredCapabilities), toolSlots: ensureArray(item.toolSlots), thoughtChain: ensureArray(item.thoughtChain), blueprintJson: parseSchemaValue(item.blueprintJson), version: item.version || "", enabled: item.enabled ?? true });
  if (activeView.value === "serverRegistry") writeEditors.serverRegistry = stringifyPretty({ serverCode: item.serverCode || item.name || "", serverName: item.name || "", description: item.description || "", baseUrl: "", transportType: "HTTP", authType: "BEARER", authConfig: {}, enabled: true, healthStatus: "UNKNOWN" });
}

async function handleSyncCatalog() { actionLoading.value = true; try { writeResult.value = await syncMcpCatalog(); showToast("能力目录同步已触发", "success"); await refreshCurrentView(); } catch (error) { showToast(`同步失败：${error?.message || String(error)}`, "error", 3200); } finally { actionLoading.value = false; } }
async function runToolCall() { if (!toolCallForm.toolName.trim()) return showToast("请先填写 toolName", "error"); actionLoading.value = true; try { debugResult.value = await callMcpTool({ serverCode: toolCallForm.serverCode || undefined, toolName: toolCallForm.toolName.trim(), argumentsJson: toJsonString(toolCallForm.argumentsJson, "{}") || "{}" }); } catch (error) { showToast(`工具调用失败：${error?.message || String(error)}`, "error", 3200); } finally { actionLoading.value = false; } }
async function runPromptGet() { if (!promptDebugForm.promptName.trim()) return showToast("请先填写 promptName", "error"); actionLoading.value = true; try { debugResult.value = await getMcpPrompt({ serverCode: promptDebugForm.serverCode || undefined, promptName: promptDebugForm.promptName.trim(), argumentsJson: toJsonString(promptDebugForm.argumentsJson, "{}") || "{}" }); } catch (error) { showToast(`Prompt 试读失败：${error?.message || String(error)}`, "error", 3200); } finally { actionLoading.value = false; } }
async function runResourceRead() { if (!resourceDebugForm.resourceUri.trim()) return showToast("请先填写 resourceUri", "error"); actionLoading.value = true; try { debugResult.value = await readMcpCatalogResource({ serverCode: resourceDebugForm.serverCode || undefined, resourceUri: resourceDebugForm.resourceUri.trim() }); } catch (error) { showToast(`资源读取失败：${error?.message || String(error)}`, "error", 3200); } finally { actionLoading.value = false; } }
async function saveWritePayload() { actionLoading.value = true; try { const payload = toJsonPayload(writeEditor.value, {}); if (activeView.value === "toolCatalog") writeResult.value = await saveMcpToolCatalog(payload); if (activeView.value === "promptCatalog") writeResult.value = await saveMcpPromptCatalog(payload); if (activeView.value === "resourceCatalog") writeResult.value = await saveMcpResourceCatalog(payload); if (activeView.value === "workflowTemplate") writeResult.value = await saveWorkflowTemplate(payload); if (activeView.value === "serverRegistry") writeResult.value = await saveMcpServerRegistry(payload); if (activeView.value === "implMapping") writeResult.value = await saveMcpToolImplMapping(payload); showToast("目录写入成功，正在刷新当前视图", "success"); await refreshCurrentView(); } catch (error) { showToast(`提交失败：${error?.message || String(error)}`, "error", 3400); } finally { actionLoading.value = false; } }
async function runRpc() { actionLoading.value = true; try { debugResult.value = await callMcpRpc(toJsonPayload(rpcEditor.value, {})); } catch (error) { showToast(`RPC 调用失败：${error?.message || String(error)}`, "error", 3200); } finally { actionLoading.value = false; } }

onMounted(refreshCurrentView);
</script>

<style scoped>
.capability-center-panel :deep(.panel-body) { padding: 14px; gap: 10px; overflow: hidden; }
.toolbar { display: grid; grid-template-columns: minmax(260px, 1.2fr) 220px auto; gap: 10px; align-items: center; flex-shrink: 0; }
.field, .detail-pane textarea, .detail-pane input, .detail-pane select { width: 100%; background: color-mix(in oklab, black 72%, transparent); border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.12)) 68%, transparent); color: var(--text-main, #fff); border-radius: 8px; padding: 9px 11px; font-size: 12px; box-sizing: border-box; }
.field:focus, .detail-pane textarea:focus, .detail-pane input:focus, .detail-pane select:focus { outline: none; border-color: var(--primary, #00ffc8); }
.toolbar-actions, .row, .between, .wrap { display: flex; gap: 8px; align-items: center; }
.between { justify-content: space-between; }
.wrap { flex-wrap: wrap; }
.toolbar-actions { justify-content: flex-end; flex-wrap: wrap; }
.btn-primary, .btn-secondary { border-radius: 8px; padding: 8px 12px; border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.14)) 68%, transparent); cursor: pointer; font-size: 12px; }
.btn-primary { background: var(--primary, #00ffc8); color: #000; border-color: transparent; font-weight: 700; }
.btn-secondary { background: rgba(255,255,255,0.06); color: var(--text-main, #f2fbf7); }
.btn-secondary.active { border-color: var(--primary, #00ffc8); color: var(--primary, #00ffc8); }
.toolbar-toast { padding: 8px 12px; border-radius: 8px; font-size: 12px; border: 1px solid; }
.toolbar-toast.info { background: rgba(59,130,246,0.14); border-color: rgba(59,130,246,0.4); color: #93c5fd; }
.toolbar-toast.success { background: rgba(34,197,94,0.14); border-color: rgba(34,197,94,0.4); color: #86efac; }
.toolbar-toast.error { background: rgba(239,68,68,0.14); border-color: rgba(239,68,68,0.4); color: #fca5a5; }
.layout-grid { flex: 1; min-height: 0; display: grid; grid-template-columns: 210px minmax(0, 1fr) minmax(360px, 420px); gap: 10px; }
.nav-pane, .list-pane, .detail-pane { min-height: 0; border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent); border-radius: 10px; background: rgba(255,255,255,0.03); overflow: hidden; }
.nav-pane { display: flex; flex-direction: column; padding: 10px; gap: 8px; }
.nav-item { display: flex; flex-direction: column; gap: 3px; text-align: left; padding: 10px 12px; border-radius: 8px; border: 1px solid transparent; background: transparent; color: var(--text-main, #e8fff8); cursor: pointer; }
.nav-item small, .muted { color: var(--text-dim, #8fa5b3); font-size: 11px; }
.nav-item.active { border-color: color-mix(in oklab, var(--primary, #00ffc8) 42%, transparent); background: linear-gradient(90deg, color-mix(in oklab, var(--primary, #00ffc8) 14%, transparent), transparent); }
.section-head { display: flex; justify-content: space-between; gap: 10px; align-items: center; padding: 10px 12px; border-bottom: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent); }
.section-head strong { display: block; color: var(--primary, #00ffc8); font-size: 12px; }
.section-head small { color: var(--text-dim, #92a8b5); font-size: 11px; }
.list-pane, .detail-pane { display: flex; flex-direction: column; }
.card-list, .row-list, .detail-scroll { flex: 1; min-height: 0; overflow: auto; padding: 12px; }
.card-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
.resource-card, .row-card { text-align: left; border-radius: 10px; border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent); background: rgba(255,255,255,0.03); color: var(--text-main, #e7f5ef); cursor: pointer; }
.resource-card { display: flex; flex-direction: column; gap: 8px; padding: 12px; }
.row-card { display: flex; justify-content: space-between; gap: 10px; align-items: flex-start; width: 100%; padding: 12px; margin-bottom: 8px; }
.resource-card.selected, .row-card.selected { border-color: color-mix(in oklab, var(--primary, #00ffc8) 42%, transparent); box-shadow: 0 0 0 1px color-mix(in oklab, var(--primary, #00ffc8) 22%, transparent); }
.row-card-main { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.row-card-main span { font-size: 12px; color: var(--text-dim, #b2c3cc); }
.row-card-meta { display: flex; flex-direction: column; gap: 6px; align-items: flex-end; font-size: 11px; color: var(--text-dim, #8fa5b3); }
.desc { margin: 0; font-size: 12px; color: var(--text-dim, #b2c3cc); line-height: 1.55; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.panel-card { display: flex; flex-direction: column; gap: 10px; padding: 12px; border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent); border-radius: 10px; background: rgba(0,0,0,0.22); margin-bottom: 10px; }
.card-title { color: var(--primary, #00ffc8); font-size: 12px; font-weight: 700; }
.detail-row { display: grid; grid-template-columns: 70px 1fr; gap: 10px; font-size: 12px; }
.detail-row label { color: var(--text-dim, #8fa5b3); }
.detail-row span { line-height: 1.55; word-break: break-word; }
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.code-input { min-height: 110px; resize: vertical; font-family: "Consolas", "Monaco", monospace; font-size: 11px; line-height: 1.55; }
.code-input.tall { min-height: 220px; }
.empty-state { padding: 24px 14px; text-align: center; color: var(--text-dim, #8ea3b2); font-size: 12px; }
.detail-empty { border: 1px dashed color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 48%, transparent); border-radius: 10px; }
.detail-empty.warning { color: #fcd34d; border-color: rgba(245,158,11,0.3); background: rgba(245,158,11,0.08); }
.card-list::-webkit-scrollbar, .row-list::-webkit-scrollbar, .detail-scroll::-webkit-scrollbar { width: 8px; height: 8px; }
.card-list::-webkit-scrollbar-thumb, .row-list::-webkit-scrollbar-thumb, .detail-scroll::-webkit-scrollbar-thumb { background: linear-gradient(180deg, color-mix(in oklab, var(--primary, #00ffc8) 45%, transparent), color-mix(in oklab, var(--primary-2, #00aaff) 45%, transparent)); border-radius: 999px; }
.toast-fade-enter-active, .toast-fade-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.toast-fade-enter-from, .toast-fade-leave-to { opacity: 0; transform: translateY(-4px); }
@media (max-width: 1280px) { .layout-grid { grid-template-columns: 190px minmax(0, 1fr); grid-template-rows: minmax(240px, 1fr) minmax(280px, 0.95fr); } .detail-pane { grid-column: 1 / span 2; } }
</style>
