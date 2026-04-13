<template>
  <FloatingPanelShell
    panelClass="prompt-governance-panel"
    title="PROMPT GOVERNANCE CENTER"
    subtitle="Prompt Search / Detail / Version / Preview / Policy"
    :initial-x="initialX"
    :initial-y="initialY"
    :initial-width="1360"
    :initial-height="820"
    :min-width="1080"
    :min-height="680"
    @close="$emit('close')"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div class="toolbar">
      <div class="mode-switch">
        <button class="btn-secondary" :class="{ active: mode === 'prompt' }" @click="switchMode('prompt')">Prompt 条目</button>
        <button class="btn-secondary" :class="{ active: mode === 'policy' }" @click="switchMode('policy')">策略管理</button>
      </div>
      <div class="toolbar-actions">
        <button v-if="mode === 'prompt'" class="btn-secondary" @click="prepareNewPrompt">新建 Prompt</button>
        <button v-else class="btn-secondary" @click="prepareNewPolicy">新建策略</button>
        <button class="btn-secondary" @click="refreshCurrentMode">{{ refreshButtonText }}</button>
      </div>
    </div>

    <transition name="toast-fade">
      <div v-if="toast.text" class="toolbar-toast" :class="toast.type">{{ toast.text }}</div>
    </transition>

    <template v-if="mode === 'prompt'">
      <div class="main-grid">
        <aside class="pane side-pane">
          <div class="pane-head">
            <div>
              <strong>分类树</strong>
              <small>分类导航 / 新建默认值</small>
            </div>
          </div>

          <div class="tree-scroll">
            <button class="tree-node" :class="{ active: !selectedCategoryKey }" @click="clearCategorySelection">
              全部分类
            </button>
            <button
              v-for="node in flatCategoryTree"
              :key="node.categoryKey"
              class="tree-node"
              :class="{ active: selectedCategoryKey === node.categoryKey }"
              :style="{ paddingLeft: `${14 + node.depth * 18}px` }"
              @click="selectCategoryNode(node)"
            >
              <span>{{ node.categoryName || node.categoryKey }}</span>
              <StatusBadge :label="node.executionCategory ? '执行类' : '内容类'" :tone="node.executionCategory ? 'WARNING' : 'ACTIVE'" />
            </button>
          </div>

          <div v-if="selectedCategoryDetail" class="info-card">
            <div class="card-title">分类详情</div>
            <div class="info-line"><span>categoryKey</span><strong>{{ selectedCategoryDetail.categoryKey }}</strong></div>
            <div class="info-line"><span>categoryName</span><strong>{{ selectedCategoryDetail.categoryName || '-' }}</strong></div>
            <div class="info-line"><span>keywordMatchAllowed</span><strong>{{ selectedCategoryDetail.keywordMatchAllowed ? 'true' : 'false' }}</strong></div>
            <div class="info-line"><span>executionCategory</span><strong>{{ selectedCategoryDetail.executionCategory ? 'true' : 'false' }}</strong></div>
          </div>
        </aside>

        <section class="pane list-pane">
          <div class="pane-head">
            <div>
              <strong>Prompt 列表</strong>
              <small>搜索结果只返回当前页数组，采用弱分页</small>
            </div>
            <span class="muted">{{ promptList.length }} 条</span>
          </div>

          <div class="filters-grid">
            <input v-model.trim="promptFilters.keyLike" class="field" placeholder="keyLike" />
            <input v-model.trim="promptFilters.nameLike" class="field" placeholder="nameLike" />
            <input v-model.trim="promptFilters.valueLike" class="field" placeholder="valueLike" />
            <input v-model.trim="promptFilters.subCategory" class="field" placeholder="subCategory" />
            <input v-model.trim="promptFilters.assemblyMode" class="field" placeholder="assemblyMode" />
            <select v-model="promptFilters.enabled" class="field">
              <option value="all">enabled（全部）</option>
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
            <select v-model="promptFilters.hasTemplateVariables" class="field">
              <option value="all">hasTemplateVariables（全部）</option>
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
            <select v-model="promptFilters.keywordMatchEnabled" class="field">
              <option value="all">keywordMatchEnabled（全部）</option>
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
            <label class="inline-check compact">
              <input v-model="promptFilters.includeDisabled" type="checkbox" />
              <span>includeDisabled</span>
            </label>
          </div>

          <div class="list-actions">
            <div class="pager-mini">
              <button class="btn-secondary" :disabled="promptFilters.pageNo <= 1 || promptLoading" @click="changePromptPage(promptFilters.pageNo - 1)">上一页</button>
              <span>第 {{ promptFilters.pageNo }} 页 / pageSize {{ promptFilters.pageSize }}</span>
              <button class="btn-secondary" :disabled="!promptHasMore || promptLoading" @click="changePromptPage(promptFilters.pageNo + 1)">下一页</button>
            </div>
            <div class="toolbar-actions">
              <button class="btn-secondary" @click="resetPromptSearch">重置</button>
              <button class="btn-primary" :disabled="promptLoading" @click="searchPrompts(true)">{{ promptLoading ? '搜索中...' : '搜索' }}</button>
            </div>
          </div>

          <div class="list-scroll">
            <div v-if="promptLoading" class="empty-state">Prompt 列表加载中...</div>
            <div v-else-if="!promptList.length" class="empty-state">暂无匹配 Prompt</div>
            <button
              v-for="item in promptList"
              :key="item.key"
              class="list-card"
              :class="{ selected: selectedPromptKey === item.key }"
              @click="selectPrompt(item.key)"
            >
              <div class="card-top">
                <strong>{{ item.name || item.key }}</strong>
                <StatusBadge :label="item.enabled ? 'enabled' : 'disabled'" :tone="item.enabled ? 'ACTIVE' : 'ERROR'" />
              </div>
              <div class="card-meta">{{ item.key }}</div>
              <div class="card-meta">{{ item.category || '-' }} / {{ item.subCategory || '-' }} / {{ item.runtimeSlot || '-' }}</div>
              <p class="card-desc">{{ item.description || item.value || '暂无描述' }}</p>
            </button>
          </div>
        </section>

        <section class="pane detail-pane">
          <div class="pane-head">
            <div>
              <strong>{{ isCreatingPrompt ? '新建 Prompt' : (selectedPromptKey || '详情与编辑') }}</strong>
              <small>详情回填 / 正式保存 / 草稿 / 删除 / 预览</small>
            </div>
            <div class="toolbar-actions">
              <button class="btn-secondary" :disabled="!selectedPromptKey" @click="openPromptDrawer('versions')">版本</button>
              <button class="btn-secondary" :disabled="!selectedPromptKey" @click="openPromptDrawer('diff')">差异</button>
              <button class="btn-secondary" :disabled="!selectedPromptKey" @click="openPromptDrawer('preview')">预览</button>
              <button class="btn-secondary" :disabled="!selectedPromptKey || promptSaving" @click="savePromptDraftAction">保存草稿</button>
              <button class="btn-secondary danger" :disabled="!selectedPromptKey || isCreatingPrompt || promptSaving" @click="deletePromptAction">删除</button>
              <button class="btn-primary" :disabled="promptSaving" @click="savePromptAction">{{ promptSaving ? '保存中...' : '正式保存' }}</button>
            </div>
          </div>

          <div class="detail-scroll">
            <div class="form-grid two">
              <label class="form-block">
                <span>key</span>
                <input v-model.trim="promptForm.key" class="field" :disabled="!isCreatingPrompt" placeholder="唯一键" />
              </label>
              <label class="form-block">
                <span>promptName</span>
                <input v-model.trim="promptForm.promptName" class="field" placeholder="展示名称" />
              </label>
              <label class="form-block">
                <span>category</span>
                <select v-model="promptForm.category" class="field">
                  <option value="">请选择分类</option>
                  <option v-for="item in categoryDetails" :key="item.categoryKey" :value="item.categoryKey">{{ item.categoryName || item.categoryKey }}</option>
                </select>
              </label>
              <label class="form-block">
                <span>subCategory</span>
                <input v-model.trim="promptForm.subCategory" class="field" placeholder="二级分类" />
              </label>
              <label class="form-block">
                <span>runtimeSlot</span>
                <input v-model.trim="promptForm.runtimeSlot" class="field" placeholder="运行时槽位" />
              </label>
              <label class="form-block">
                <span>assemblyMode</span>
                <input v-model.trim="promptForm.assemblyMode" class="field" placeholder="组装模式" />
              </label>
            </div>

            <label class="form-block">
              <span>description</span>
              <textarea v-model.trim="promptForm.description" class="field code-input"></textarea>
            </label>

            <label class="form-block">
              <span>value</span>
              <textarea v-model="promptForm.value" class="field code-input tall" placeholder="Prompt 正文"></textarea>
            </label>

            <div class="form-grid three">
              <label class="inline-check"><input v-model="promptForm.enabled" type="checkbox" /><span>enabled</span></label>
              <label class="inline-check"><input v-model="promptForm.hasTemplateVariables" type="checkbox" /><span>hasTemplateVariables</span></label>
              <label class="inline-check"><input v-model="promptForm.keywordMatchEnabled" type="checkbox" /><span>keywordMatchEnabled</span></label>
            </div>

            <div class="form-grid two">
              <label class="form-block">
                <span>templateVariables</span>
                <textarea v-model="promptForm.templateVariablesText" class="field code-input" placeholder="逗号或换行分隔"></textarea>
              </label>
              <label class="form-block">
                <span>matchKeywords</span>
                <textarea v-model="promptForm.matchKeywordsText" class="field code-input" placeholder="逗号或换行分隔"></textarea>
              </label>
            </div>

            <div class="form-grid two">
              <label class="form-block">
                <span>matchScope</span>
                <textarea v-model="promptForm.matchScopeText" class="field code-input tall" placeholder='{"agents":[],"nodeKinds":[],"taskStates":[],"modelFamilies":[],"personaIds":[],"sceneIds":[]}'></textarea>
              </label>
              <label class="form-block">
                <span>editPolicy</span>
                <textarea v-model="promptForm.editPolicyText" class="field code-input tall" placeholder='{"create":true,"update":true,"delete":true}'></textarea>
              </label>
            </div>

            <div class="form-grid four">
              <label class="form-block">
                <span>priority</span>
                <input v-model.number="promptForm.priority" type="number" class="field" />
              </label>
              <label class="form-block">
                <span>status</span>
                <input v-model.trim="promptForm.status" class="field" placeholder="enabled / disabled / active / draft" />
              </label>
              <label class="form-block">
                <span>version</span>
                <input v-model.trim="promptForm.version" class="field" placeholder="版本号" />
              </label>
              <label class="form-block">
                <span>versionLabel</span>
                <input v-model.trim="promptForm.versionLabel" class="field" placeholder="版本标签" />
              </label>
            </div>

            <label class="form-block">
              <span>changeNote</span>
              <textarea v-model.trim="promptForm.changeNote" class="field code-input" placeholder="变更说明"></textarea>
            </label>
          </div>
        </section>
      </div>

      <div v-if="promptDrawer" class="drawer-panel">
        <div class="drawer-head">
          <strong>{{ promptDrawerTitle }}</strong>
          <button class="btn-secondary" @click="promptDrawer = ''">关闭抽屉</button>
        </div>

        <div v-if="promptDrawer === 'versions'" class="drawer-grid">
          <div class="drawer-list">
            <div v-if="versionLoading" class="empty-state">版本列表加载中...</div>
            <div v-else-if="!promptVersions.length" class="empty-state">暂无版本数据</div>
            <button v-for="item in promptVersions" :key="item.id" class="list-card compact" :class="{ selected: selectedVersionId === item.id }" @click="selectVersion(item.id)">
              <div class="card-top">
                <strong>{{ item.versionNo || item.versionLabel || item.id }}</strong>
                <StatusBadge :label="item.isActive ? 'active' : item.status || '-'" :tone="item.isActive ? 'ACTIVE' : item.status" />
              </div>
              <div class="card-meta">{{ item.createdAt || '-' }}</div>
              <div class="card-meta">{{ item.changeNote || '-' }}</div>
              <div class="inline-actions">
                <button class="mini-btn" @click.stop="pickDiffVersion('left', item)">设左</button>
                <button class="mini-btn" @click.stop="pickDiffVersion('right', item)">设右</button>
                <button class="mini-btn" :disabled="item.isActive" @click.stop="activateVersionAction(item.id)">激活</button>
                <button class="mini-btn" @click.stop="rollbackVersionAction(item.id)">回滚</button>
                <button class="mini-btn danger" @click.stop="archiveVersionAction(item.id)">归档</button>
              </div>
            </button>
          </div>

          <div class="drawer-detail">
            <div class="inline-actions">
              <button class="btn-secondary" :disabled="!diffSelection.leftVersionId || !diffSelection.rightVersionId" @click="openPromptDrawer('diff')">去查看差异</button>
            </div>
            <JsonPreviewBlock title="版本详情" :value="selectedVersionDetail || {}" :max-height="420" />
          </div>
        </div>

        <div v-else-if="promptDrawer === 'diff'" class="drawer-detail">
          <div class="diff-summary">
            <span>leftVersionId: {{ diffSelection.leftVersionId || '-' }}</span>
            <span>rightVersionId: {{ diffSelection.rightVersionId || '-' }}</span>
            <button class="btn-primary" :disabled="!diffSelection.leftVersionId || !diffSelection.rightVersionId || diffLoading" @click="runVersionDiff">
              {{ diffLoading ? '对比中...' : '执行差异对比' }}
            </button>
          </div>
          <DiffViewer :lines="diffResult?.diffLines || []" />
          <JsonPreviewBlock title="差异元数据" :value="diffResult || {}" :max-height="220" />
        </div>

        <div v-else class="drawer-grid preview-grid">
          <div class="drawer-detail">
            <div class="form-grid two">
              <label class="form-block"><span>sessionId</span><input v-model.trim="previewForm.sessionId" class="field" /></label>
              <label class="form-block"><span>policyId</span><input v-model.trim="previewForm.policyId" class="field" /></label>
              <label class="form-block"><span>personaId</span><input v-model.trim="previewForm.personaId" class="field" /></label>
              <label class="form-block"><span>sceneId</span><input v-model.trim="previewForm.sceneId" class="field" /></label>
              <label class="form-block"><span>agent</span><input v-model.trim="previewForm.agent" class="field" /></label>
              <label class="form-block"><span>nodeKind</span><input v-model.trim="previewForm.nodeKind" class="field" /></label>
              <label class="form-block"><span>taskState</span><input v-model.trim="previewForm.taskState" class="field" /></label>
              <label class="form-block"><span>modelFamily</span><input v-model.trim="previewForm.modelFamily" class="field" /></label>
            </div>
            <label class="form-block"><span>userInput</span><textarea v-model.trim="previewForm.userInput" class="field code-input"></textarea></label>
            <label class="form-block"><span>manualPromptKeys</span><textarea v-model="previewForm.manualPromptKeysText" class="field code-input" placeholder="逗号或换行分隔"></textarea></label>
            <div class="inline-actions">
              <button class="btn-secondary" :disabled="previewLoading" @click="runPreview('match')">{{ previewLoading && previewMode === 'match' ? '匹配中...' : '匹配预览' }}</button>
              <button class="btn-primary" :disabled="previewLoading" @click="runPreview('assemble')">{{ previewLoading && previewMode === 'assemble' ? '组装中...' : '组装预览' }}</button>
            </div>
          </div>

          <div class="drawer-detail">
            <JsonPreviewBlock title="matchedItems" :value="previewResult?.matchedItems || []" :max-height="180" />
            <JsonPreviewBlock title="rejectedItems" :value="previewResult?.rejectedItems || []" :max-height="180" />
            <JsonPreviewBlock v-if="previewMode === 'assemble'" title="slotMapping" :value="previewResult?.slotMapping || {}" :max-height="180" />
            <JsonPreviewBlock v-if="previewMode === 'assemble'" title="assembled" :value="previewResult?.assembled || {}" :max-height="180" />
            <JsonPreviewBlock v-if="previewMode === 'assemble'" title="sectionAssembled" :value="previewResult?.sectionAssembled || {}" :max-height="180" />
            <JsonPreviewBlock v-if="previewMode === 'assemble'" title="filteredOutItems" :value="previewResult?.filteredOutItems || []" :max-height="180" />
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="policy-grid">
        <aside class="pane side-pane">
          <div class="pane-head">
            <div>
              <strong>策略列表</strong>
              <small>按 updatedAt desc 返回</small>
            </div>
          </div>
          <div class="list-scroll">
            <div v-if="policyLoading" class="empty-state">策略列表加载中...</div>
            <div v-else-if="!policyList.length" class="empty-state">暂无策略数据</div>
            <button v-for="item in policyList" :key="item.policyId" class="list-card" :class="{ selected: selectedPolicyId === item.policyId }" @click="selectPolicy(item.policyId)">
              <div class="card-top">
                <strong>{{ item.policyName || item.policyId }}</strong>
                <StatusBadge :label="item.enabled ? 'enabled' : 'disabled'" :tone="item.enabled ? 'ACTIVE' : 'ERROR'" />
              </div>
              <div class="card-meta">{{ item.policyId }}</div>
              <div class="card-meta">{{ item.updatedAt || '-' }}</div>
              <p class="card-desc">{{ item.description || '暂无描述' }}</p>
            </button>
          </div>
        </aside>

        <section class="pane detail-pane">
          <div class="pane-head">
            <div>
              <strong>{{ isCreatingPolicy ? '新建策略' : (selectedPolicyId || '策略详情') }}</strong>
              <small>保存会生成新的 active 版本</small>
            </div>
            <div class="toolbar-actions">
              <button class="btn-secondary" :disabled="!selectedPolicyId" @click="openPolicyVersions">版本</button>
              <button class="btn-secondary danger" :disabled="!selectedPolicyId || isCreatingPolicy || policySaving" @click="deletePolicyAction">删除</button>
              <button class="btn-primary" :disabled="policySaving" @click="savePolicyAction">{{ policySaving ? '保存中...' : '保存策略' }}</button>
            </div>
          </div>

          <div class="detail-scroll">
            <div class="form-grid two">
              <label class="form-block"><span>policyId</span><input v-model.trim="policyForm.policyId" class="field" :disabled="!isCreatingPolicy" /></label>
              <label class="form-block"><span>policyName</span><input v-model.trim="policyForm.policyName" class="field" /></label>
            </div>
            <label class="form-block"><span>description</span><textarea v-model.trim="policyForm.description" class="field code-input"></textarea></label>
            <label class="inline-check"><input v-model="policyForm.enabled" type="checkbox" /><span>enabled</span></label>
            <div class="form-grid two">
              <label class="form-block"><span>version</span><input v-model.trim="policyForm.version" class="field" /></label>
              <label class="form-block"><span>changeNote</span><input v-model.trim="policyForm.changeNote" class="field" /></label>
            </div>
            <label class="form-block"><span>includePromptKeys</span><textarea v-model="policyForm.includePromptKeysText" class="field code-input tall" placeholder="逗号或换行分隔"></textarea></label>
            <label class="form-block"><span>excludePromptKeys</span><textarea v-model="policyForm.excludePromptKeysText" class="field code-input tall" placeholder="逗号或换行分隔"></textarea></label>
          </div>
        </section>
      </div>

      <div v-if="policyDrawer === 'versions'" class="drawer-panel">
        <div class="drawer-head">
          <strong>策略版本</strong>
          <button class="btn-secondary" @click="policyDrawer = ''">关闭抽屉</button>
        </div>
        <div class="drawer-grid">
          <div class="drawer-list">
            <div v-if="policyVersionLoading" class="empty-state">策略版本加载中...</div>
            <div v-else-if="!policyVersions.length" class="empty-state">暂无策略版本</div>
            <button v-for="item in policyVersions" :key="item.id" class="list-card compact" :class="{ selected: selectedPolicyVersionId === item.id }" @click="selectPolicyVersion(item.id)">
              <div class="card-top">
                <strong>{{ item.versionNo || item.id }}</strong>
                <StatusBadge :label="item.isActive ? 'active' : item.status || '-'" :tone="item.isActive ? 'ACTIVE' : item.status" />
              </div>
              <div class="card-meta">{{ item.createdAt || '-' }}</div>
              <div class="card-meta">{{ item.changeNote || '-' }}</div>
              <div class="inline-actions">
                <button class="mini-btn" :disabled="item.isActive" @click.stop="activatePolicyVersionAction(item.id)">激活</button>
              </div>
            </button>
          </div>
          <div class="drawer-detail">
            <JsonPreviewBlock title="策略版本详情" :value="selectedPolicyVersionDetail || {}" :max-height="420" />
          </div>
        </div>
      </div>
    </template>
  </FloatingPanelShell>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import {
  activatePromptPolicyVersion,
  activatePromptVersion,
  archivePromptVersion,
  checkPromptItemExists,
  createPromptItem,
  deletePromptItem,
  deletePromptPolicy,
  diffPromptVersions,
  getPromptCategoryTree,
  getPromptItemDetail,
  getPromptPolicyDetail,
  getPromptVersionDetail,
  listPromptCategoryDetails,
  listPromptPolicies,
  listPromptPolicyVersions,
  listPromptVersions,
  previewPromptAssemble,
  previewPromptMatch,
  rollbackPromptVersion,
  savePromptDraft,
  savePromptPolicy,
  searchPromptItems,
  updatePromptItem,
} from "../api/index.js";
import {
  ensureArray,
  normalizePromptItem,
  normalizePromptPolicy,
  normalizePromptPolicyVersion,
  normalizePromptVersion,
  parseTextArray,
  safeJsonParse,
  stringifyPretty,
} from "../utils/data-utils.js";
import DiffViewer from "./common/DiffViewer.vue";
import FloatingPanelShell from "./common/FloatingPanelShell.vue";
import JsonPreviewBlock from "./common/JsonPreviewBlock.vue";
import StatusBadge from "./common/StatusBadge.vue";

defineEmits(["close", "mouseenter", "mouseleave"]);

const initialX = window.innerWidth / 2 - 680;
const initialY = window.innerHeight / 2 - 390;

const mode = ref("prompt");
const categoryDetails = ref([]);
const categoryTree = ref([]);
const selectedCategoryKey = ref("");

const promptFilters = reactive({
  category: "",
  subCategory: "",
  keyLike: "",
  nameLike: "",
  valueLike: "",
  assemblyMode: "",
  enabled: "all",
  hasTemplateVariables: "all",
  keywordMatchEnabled: "all",
  includeDisabled: false,
  pageNo: 1,
  pageSize: 20,
});

const promptList = ref([]);
const selectedPromptKey = ref("");
const isCreatingPrompt = ref(false);
const promptLoading = ref(false);
const promptSaving = ref(false);
const versionLoading = ref(false);
const diffLoading = ref(false);
const previewLoading = ref(false);
const promptVersions = ref([]);
const selectedVersionId = ref("");
const selectedVersionDetail = ref(null);
const diffResult = ref(null);
const promptDrawer = ref("");
const previewMode = ref("match");
const previewResult = ref(null);
const diffSelection = reactive({
  leftVersionId: "",
  rightVersionId: "",
});

const policyList = ref([]);
const selectedPolicyId = ref("");
const isCreatingPolicy = ref(false);
const policySaving = ref(false);
const policyLoading = ref(false);
const policyDrawer = ref("");
const policyVersions = ref([]);
const policyVersionLoading = ref(false);
const selectedPolicyVersionId = ref("");
const selectedPolicyVersionDetail = ref(null);

const toast = reactive({ text: "", type: "info" });
let toastTimer = null;

function showToast(text, type = "info", duration = 2600) {
  toast.text = text;
  toast.type = type;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.text = "";
  }, duration);
}

function defaultPromptForm(category = "", subCategory = "") {
  return {
    key: "",
    promptName: "",
    value: "",
    category,
    subCategory,
    description: "",
    runtimeSlot: "",
    hasTemplateVariables: false,
    templateVariablesText: "",
    keywordMatchEnabled: false,
    matchKeywordsText: "",
    assemblyMode: "",
    matchScopeText: stringifyPretty({
      agents: [],
      nodeKinds: [],
      taskStates: [],
      modelFamilies: [],
      personaIds: [],
      sceneIds: [],
    }, "{}"),
    editPolicyText: stringifyPretty({ create: true, update: true, delete: true }, "{}"),
    enabled: true,
    priority: 80,
    status: "enabled",
    version: "",
    versionLabel: "",
    changeNote: "",
  };
}

function defaultPolicyForm() {
  return {
    policyId: "",
    policyName: "",
    description: "",
    enabled: true,
    includePromptKeysText: "",
    excludePromptKeysText: "",
    version: "",
    changeNote: "",
  };
}

function defaultPreviewForm() {
  return {
    sessionId: "",
    userInput: "",
    policyId: "",
    personaId: "",
    sceneId: "",
    agent: "",
    nodeKind: "",
    taskState: "",
    modelFamily: "",
    manualPromptKeysText: "",
  };
}

const promptForm = reactive(defaultPromptForm());
const policyForm = reactive(defaultPolicyForm());
const previewForm = reactive(defaultPreviewForm());

function overwriteReactive(target, source) {
  Object.keys(target).forEach((key) => {
    target[key] = source[key];
  });
}

function flattenTree(nodes, depth = 0, parentCategoryKey = "") {
  return ensureArray(nodes).flatMap((item) => {
    const current = {
      ...item,
      depth,
      parentCategoryKey: item?.parentCategoryKey ?? parentCategoryKey ?? "",
    };
    return [current, ...flattenTree(item?.children, depth + 1, item?.categoryKey || "")];
  });
}

const flatCategoryTree = computed(() => flattenTree(categoryTree.value));
const categoryDetailMap = computed(() => Object.fromEntries(categoryDetails.value.map((item) => [item.categoryKey, item])));
const selectedCategoryDetail = computed(() => categoryDetailMap.value[selectedCategoryKey.value] || null);
const promptHasMore = computed(() => promptList.value.length >= Number(promptFilters.pageSize || 20));
const refreshButtonText = computed(() => {
  if (mode.value === "prompt") {
    return promptLoading.value ? "刷新中..." : "刷新";
  }
  return policyLoading.value ? "刷新中..." : "刷新";
});
const promptDrawerTitle = computed(() => {
  if (promptDrawer.value === "versions") return "Prompt 版本抽屉";
  if (promptDrawer.value === "diff") return "Prompt 差异对比";
  return "Prompt 预览抽屉";
});

function normalizeFilterBoolean(value) {
  if (value === "all") return undefined;
  if (value === "true") return true;
  if (value === "false") return false;
  return undefined;
}

function getCategorySeed() {
  const current = flatCategoryTree.value.find((item) => item.categoryKey === selectedCategoryKey.value);
  if (!current) return { category: "", subCategory: "" };
  if (current.parentCategoryKey) {
    return { category: current.parentCategoryKey, subCategory: current.categoryKey };
  }
  return { category: current.categoryKey, subCategory: "" };
}

async function loadCategoryMeta() {
  const [details, tree] = await Promise.all([
    listPromptCategoryDetails(),
    getPromptCategoryTree(),
  ]);
  categoryDetails.value = ensureArray(details);
  categoryTree.value = ensureArray(tree);
}

function buildPromptSearchPayload() {
  const payload = {
    category: promptFilters.category || undefined,
    subCategory: promptFilters.subCategory || undefined,
    keyLike: promptFilters.keyLike || undefined,
    nameLike: promptFilters.nameLike || undefined,
    valueLike: promptFilters.valueLike || undefined,
    assemblyMode: promptFilters.assemblyMode || undefined,
    includeDisabled: !!promptFilters.includeDisabled,
    pageNo: Number(promptFilters.pageNo || 1),
    pageSize: Number(promptFilters.pageSize || 20),
  };
  const enabled = normalizeFilterBoolean(promptFilters.enabled);
  const hasTemplateVariables = normalizeFilterBoolean(promptFilters.hasTemplateVariables);
  const keywordMatchEnabled = normalizeFilterBoolean(promptFilters.keywordMatchEnabled);
  if (enabled !== undefined) payload.enabled = enabled;
  if (hasTemplateVariables !== undefined) payload.hasTemplateVariables = hasTemplateVariables;
  if (keywordMatchEnabled !== undefined) payload.keywordMatchEnabled = keywordMatchEnabled;
  return payload;
}

async function searchPrompts(resetPage = false) {
  promptLoading.value = true;
  if (resetPage) {
    promptFilters.pageNo = 1;
  }
  try {
    const list = await searchPromptItems(buildPromptSearchPayload());
    promptList.value = ensureArray(list).map((item) => normalizePromptItem(item));
  } catch (error) {
    promptList.value = [];
    showToast(error?.message || "Prompt 搜索失败", "error", 3200);
  } finally {
    promptLoading.value = false;
  }
}

function changePromptPage(nextPage) {
  promptFilters.pageNo = nextPage;
  searchPrompts(false);
}

function resetPromptSearch() {
  const seed = getCategorySeed();
  promptFilters.category = seed.category;
  promptFilters.subCategory = seed.subCategory;
  promptFilters.keyLike = "";
  promptFilters.nameLike = "";
  promptFilters.valueLike = "";
  promptFilters.assemblyMode = "";
  promptFilters.enabled = "all";
  promptFilters.hasTemplateVariables = "all";
  promptFilters.keywordMatchEnabled = "all";
  promptFilters.includeDisabled = false;
  promptFilters.pageNo = 1;
  searchPrompts(true);
}

function clearCategorySelection() {
  selectedCategoryKey.value = "";
  promptFilters.category = "";
  promptFilters.subCategory = "";
  promptFilters.pageNo = 1;
  searchPrompts(true);
}

function selectCategoryNode(node) {
  selectedCategoryKey.value = node.categoryKey;
  if (node.parentCategoryKey) {
    promptFilters.category = node.parentCategoryKey;
    promptFilters.subCategory = node.categoryKey;
  } else {
    promptFilters.category = node.categoryKey;
    promptFilters.subCategory = "";
  }
  promptFilters.pageNo = 1;
  searchPrompts(true);
}

function applyPromptToForm(item) {
  const data = normalizePromptItem(item);
  overwriteReactive(promptForm, {
    key: data.key || "",
    promptName: data.name || "",
    value: data.value || "",
    category: data.category || "",
    subCategory: data.subCategory || "",
    description: data.description || "",
    runtimeSlot: data.runtimeSlot || "",
    hasTemplateVariables: !!data.hasTemplateVariables,
    templateVariablesText: data.templateVariables.join("\n"),
    keywordMatchEnabled: !!data.keywordMatchEnabled,
    matchKeywordsText: data.matchKeywords.join("\n"),
    assemblyMode: data.assemblyMode || "",
    matchScopeText: stringifyPretty(data.matchScope || {}, "{}"),
    editPolicyText: stringifyPretty(data.editPolicy || {}, "{}"),
    enabled: !!data.enabled,
    priority: Number(data.priority ?? 80),
    status: data.status || "",
    version: data.version || "",
    versionLabel: data.versionLabel || "",
    changeNote: data.changeNote || "",
  });
}

function prepareNewPrompt() {
  const seed = getCategorySeed();
  isCreatingPrompt.value = true;
  selectedPromptKey.value = "";
  overwriteReactive(promptForm, defaultPromptForm(seed.category, seed.subCategory));
}

async function selectPrompt(key) {
  if (!key) return;
  try {
    const detail = await updateSelectPrompt(key);
    return detail;
  } catch (error) {
    showToast(error?.message || "Prompt 详情加载失败", "error", 3200);
    return null;
  }
}

async function updateSelectPrompt(key) {
  const item = normalizePromptItem(await getPromptItemDetail({ key }));
  selectedPromptKey.value = item.key || key;
  isCreatingPrompt.value = false;
  applyPromptToForm(item);
  return item;
}

function buildPromptPayload() {
  const matchScope = safeJsonParse(promptForm.matchScopeText, undefined);
  if (matchScope === undefined) {
    throw new Error("matchScope JSON 格式不合法");
  }

  const editPolicy = safeJsonParse(promptForm.editPolicyText, undefined);
  if (editPolicy === undefined) {
    throw new Error("editPolicy JSON 格式不合法");
  }

  const payload = {
    key: promptForm.key.trim(),
    promptName: promptForm.promptName.trim() || undefined,
    value: promptForm.value,
    category: promptForm.category || undefined,
    subCategory: promptForm.subCategory.trim() || undefined,
    description: promptForm.description.trim() || undefined,
    runtimeSlot: promptForm.runtimeSlot.trim() || undefined,
    hasTemplateVariables: !!promptForm.hasTemplateVariables,
    templateVariables: parseTextArray(promptForm.templateVariablesText),
    keywordMatchEnabled: !!promptForm.keywordMatchEnabled,
    matchKeywords: parseTextArray(promptForm.matchKeywordsText),
    assemblyMode: promptForm.assemblyMode.trim() || undefined,
    matchScope,
    editPolicy,
    enabled: !!promptForm.enabled,
    priority: Number(promptForm.priority ?? 0),
    status: promptForm.status.trim() || undefined,
    version: promptForm.version.trim() || undefined,
    versionLabel: promptForm.versionLabel.trim() || undefined,
    changeNote: promptForm.changeNote.trim() || undefined,
  };

  Object.keys(payload).forEach((key) => {
    const value = payload[key];
    if (value === undefined || value === null || value === "") {
      delete payload[key];
    }
  });

  return payload;
}

function validatePromptPayload(payload) {
  if (!payload.key) throw new Error("key 不能为空");
  if (!payload.category) throw new Error("category 不能为空");
  if (!payload.subCategory) throw new Error("subCategory 不能为空");

  const parts = payload.key.split(".");
  if (parts.length >= 2 && (parts[0] !== payload.category || parts[1] !== payload.subCategory)) {
    throw new Error("key 前缀必须与 category / subCategory 一致");
  }

  const categoryMeta = categoryDetailMap.value[payload.category] || {};
  const isExecutionCategory = !!categoryMeta.executionCategory;
  if (isCreatingPrompt.value && isExecutionCategory) {
    throw new Error("create only supports content prompt category");
  }
  if (!isExecutionCategory && (payload.hasTemplateVariables || ensureArray(payload.templateVariables).length)) {
    throw new Error("内容类 Prompt 不能携带 templateVariables");
  }
}

async function savePromptAction() {
  promptSaving.value = true;
  try {
    const payload = buildPromptPayload();
    validatePromptPayload(payload);

    if (isCreatingPrompt.value) {
      const exists = await checkPromptItemExists({ key: payload.key });
      if (exists?.exists) throw new Error("prompt key already exists");
      await createPromptItem(payload);
    } else {
      await updatePromptItem(payload);
    }

    await updateSelectPrompt(payload.key);
    await searchPrompts(false);
    if (promptDrawer.value === "versions") {
      await loadPromptVersions(payload.key);
    }
    showToast("Prompt 保存成功", "success");
  } catch (error) {
    showToast(error?.message || "Prompt 保存失败", "error", 3400);
  } finally {
    promptSaving.value = false;
  }
}

async function deletePromptAction() {
  if (!selectedPromptKey.value) return;
  if (!window.confirm("软删除仅禁用当前 Prompt，不会清除历史版本，确认继续吗？")) return;
  promptSaving.value = true;
  try {
    await deletePromptItem({ key: selectedPromptKey.value });
    showToast("Prompt 已软删除", "success");
    prepareNewPrompt();
    promptVersions.value = [];
    selectedVersionId.value = "";
    selectedVersionDetail.value = null;
    await searchPrompts(false);
  } catch (error) {
    showToast(error?.message || "Prompt 删除失败", "error", 3200);
  } finally {
    promptSaving.value = false;
  }
}

async function savePromptDraftAction() {
  if (!selectedPromptKey.value) {
    showToast("请先选择已存在的 Prompt", "error");
    return;
  }
  promptSaving.value = true;
  try {
    const payload = buildPromptPayload();
    validatePromptPayload(payload);
    await savePromptDraft(payload);
    await loadPromptVersions(selectedPromptKey.value);
    showToast("草稿版本已保存", "success");
  } catch (error) {
    showToast(error?.message || "草稿保存失败", "error", 3200);
  } finally {
    promptSaving.value = false;
  }
}

async function loadPromptVersions(key = selectedPromptKey.value) {
  if (!key) return;
  versionLoading.value = true;
  try {
    promptVersions.value = ensureArray(await listPromptVersions({ key })).map((item) => normalizePromptVersion(item));
    if (promptVersions.value.length) {
      await selectVersion(promptVersions.value[0].id);
    } else {
      selectedVersionId.value = "";
      selectedVersionDetail.value = null;
    }
  } finally {
    versionLoading.value = false;
  }
}

async function selectVersion(versionId) {
  if (!versionId) return;
  selectedVersionId.value = versionId;
  selectedVersionDetail.value = normalizePromptVersion(await getPromptVersionDetail({ versionId }));
}

function pickDiffVersion(side, item) {
  if (side === "left") diffSelection.leftVersionId = item.id;
  else diffSelection.rightVersionId = item.id;
}

async function activateVersionAction(versionId) {
  await activatePromptVersion({ versionId });
  await updateSelectPrompt(selectedPromptKey.value);
  await loadPromptVersions(selectedPromptKey.value);
  await searchPrompts(false);
  showToast("版本已激活", "success");
}

async function rollbackVersionAction(versionId) {
  await rollbackPromptVersion({ key: selectedPromptKey.value, versionId });
  await updateSelectPrompt(selectedPromptKey.value);
  await loadPromptVersions(selectedPromptKey.value);
  await searchPrompts(false);
  showToast("版本已回滚", "success");
}

async function archiveVersionAction(versionId) {
  await archivePromptVersion({ versionId });
  await loadPromptVersions(selectedPromptKey.value);
  await updateSelectPrompt(selectedPromptKey.value);
  await searchPrompts(false);
  showToast("版本已归档", "success");
}

function openPromptDrawer(type) {
  promptDrawer.value = type;
  if (type === "versions") {
    loadPromptVersions(selectedPromptKey.value).catch((error) => showToast(error?.message || "版本列表加载失败", "error"));
  }
}

async function runVersionDiff() {
  diffLoading.value = true;
  try {
    diffResult.value = await diffPromptVersions({
      leftVersionId: diffSelection.leftVersionId,
      rightVersionId: diffSelection.rightVersionId,
    });
  } catch (error) {
    showToast(error?.message || "版本对比失败", "error", 3200);
  } finally {
    diffLoading.value = false;
  }
}

function buildPreviewPayload() {
  const payload = {
    sessionId: previewForm.sessionId.trim() || undefined,
    userInput: previewForm.userInput.trim() || undefined,
    policyId: previewForm.policyId.trim() || undefined,
    personaId: previewForm.personaId.trim() || undefined,
    sceneId: previewForm.sceneId.trim() || undefined,
    agent: previewForm.agent.trim() || undefined,
    nodeKind: previewForm.nodeKind.trim() || undefined,
    taskState: previewForm.taskState.trim() || undefined,
    modelFamily: previewForm.modelFamily.trim() || undefined,
  };
  const manualPromptKeys = parseTextArray(previewForm.manualPromptKeysText);
  if (manualPromptKeys.length) payload.manualPromptKeys = manualPromptKeys;
  return payload;
}

async function runPreview(kind) {
  previewLoading.value = true;
  previewMode.value = kind;
  try {
    const payload = buildPreviewPayload();
    previewResult.value = kind === "match" ? await previewPromptMatch(payload) : await previewPromptAssemble(payload);
  } catch (error) {
    showToast(error?.message || "Prompt 预览失败", "error", 3200);
  } finally {
    previewLoading.value = false;
  }
}

function applyPolicyToForm(item) {
  const data = normalizePromptPolicy(item);
  overwriteReactive(policyForm, {
    policyId: data.policyId || "",
    policyName: data.policyName || "",
    description: data.description || "",
    enabled: !!data.enabled,
    includePromptKeysText: ensureArray(data.includePromptKeys).join("\n"),
    excludePromptKeysText: ensureArray(data.excludePromptKeys).join("\n"),
    version: "",
    changeNote: "",
  });
}

function prepareNewPolicy() {
  isCreatingPolicy.value = true;
  selectedPolicyId.value = "";
  overwriteReactive(policyForm, defaultPolicyForm());
}

async function loadPolicies() {
  policyLoading.value = true;
  try {
    policyList.value = ensureArray(await listPromptPolicies()).map((item) => normalizePromptPolicy(item));
  } catch (error) {
    policyList.value = [];
    showToast(error?.message || "策略列表加载失败", "error", 3200);
  } finally {
    policyLoading.value = false;
  }
}

async function selectPolicy(policyId) {
  if (!policyId) return null;

  try {
    const detail = normalizePromptPolicy(await getPromptPolicyDetail({ policyId }));
    selectedPolicyId.value = detail.policyId || policyId;
    isCreatingPolicy.value = false;
    applyPolicyToForm(detail);
    return detail;
  } catch (error) {
    showToast(error?.message || "策略详情加载失败", "error", 3200);
    return null;
  }
}

function buildPolicyPayload() {
  const policyId = policyForm.policyId.trim();
  if (!policyId) {
    throw new Error("policyId 不能为空");
  }

  return {
    policyId,
    policyName: policyForm.policyName.trim() || undefined,
    description: policyForm.description.trim() || undefined,
    enabled: !!policyForm.enabled,
    includePromptKeys: parseTextArray(policyForm.includePromptKeysText),
    excludePromptKeys: parseTextArray(policyForm.excludePromptKeysText),
    version: policyForm.version.trim() || undefined,
    changeNote: policyForm.changeNote.trim() || undefined,
  };
}

async function savePolicyAction() {
  policySaving.value = true;
  try {
    const payload = buildPolicyPayload();
    await savePromptPolicy(payload);
    await loadPolicies();
    await selectPolicy(payload.policyId);
    if (policyDrawer.value === "versions") {
      await loadPolicyVersions(payload.policyId);
    }
    showToast("策略保存成功，已生成新的 active 版本", "success", 3000);
  } catch (error) {
    showToast(error?.message || "策略保存失败", "error", 3200);
  } finally {
    policySaving.value = false;
  }
}

async function deletePolicyAction() {
  if (!selectedPolicyId.value) return;
  if (!window.confirm("删除策略为软删除，会禁用当前策略并保留历史版本，确认继续吗？")) return;

  policySaving.value = true;
  try {
    await deletePromptPolicy({ policyId: selectedPolicyId.value });
    policyDrawer.value = "";
    policyVersions.value = [];
    selectedPolicyVersionId.value = "";
    selectedPolicyVersionDetail.value = null;
    prepareNewPolicy();
    await loadPolicies();
    showToast("策略已软删除", "success");
  } catch (error) {
    showToast(error?.message || "策略删除失败", "error", 3200);
  } finally {
    policySaving.value = false;
  }
}

async function loadPolicyVersions(policyId = selectedPolicyId.value) {
  if (!policyId) return;

  policyVersionLoading.value = true;
  try {
    policyVersions.value = ensureArray(await listPromptPolicyVersions({ policyId })).map((item) =>
      normalizePromptPolicyVersion(item),
    );
    if (policyVersions.value.length) {
      selectPolicyVersion(policyVersions.value[0].id);
    } else {
      selectedPolicyVersionId.value = "";
      selectedPolicyVersionDetail.value = null;
    }
  } catch (error) {
    policyVersions.value = [];
    selectedPolicyVersionId.value = "";
    selectedPolicyVersionDetail.value = null;
    showToast(error?.message || "策略版本加载失败", "error", 3200);
  } finally {
    policyVersionLoading.value = false;
  }
}

function selectPolicyVersion(versionId) {
  selectedPolicyVersionId.value = versionId;
  selectedPolicyVersionDetail.value =
    policyVersions.value.find((item) => String(item.id) === String(versionId)) || null;
}

function openPolicyVersions() {
  policyDrawer.value = "versions";
  loadPolicyVersions(selectedPolicyId.value).catch((error) =>
    showToast(error?.message || "策略版本加载失败", "error", 3200),
  );
}

async function activatePolicyVersionAction(versionId) {
  try {
    await activatePromptPolicyVersion({
      policyId: selectedPolicyId.value,
      versionId,
    });
    await loadPolicies();
    await selectPolicy(selectedPolicyId.value);
    await loadPolicyVersions(selectedPolicyId.value);
    showToast("策略版本已激活", "success");
  } catch (error) {
    showToast(error?.message || "策略版本激活失败", "error", 3200);
  }
}

async function refreshCurrentMode() {
  if (mode.value === "prompt") {
    await loadCategoryMeta();
    await searchPrompts(false);
    return;
  }

  await loadPolicies();
}

function switchMode(nextMode) {
  if (mode.value === nextMode) return;

  mode.value = nextMode;
  promptDrawer.value = "";
  policyDrawer.value = "";
  diffResult.value = null;
  previewResult.value = null;

  refreshCurrentMode().catch((error) => {
    showToast(error?.message || "刷新失败", "error", 3200);
  });
}

onMounted(async () => {
  try {
    await Promise.all([loadCategoryMeta(), loadPolicies()]);
    await searchPrompts(true);
  } catch (error) {
    showToast(error?.message || "治理中心初始化失败", "error", 3400);
  }
});

onBeforeUnmount(() => {
  if (toastTimer) clearTimeout(toastTimer);
});
</script>

<style scoped>
.prompt-governance-panel :deep(.panel-body) {
  padding: 14px;
  gap: 10px;
  overflow: hidden;
}

.toolbar,
.toolbar-actions,
.mode-switch,
.inline-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar {
  justify-content: space-between;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.toolbar-actions {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.main-grid,
.policy-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 10px;
}

.main-grid {
  grid-template-columns: 250px minmax(320px, 0.95fr) minmax(0, 1.25fr);
}

.policy-grid {
  grid-template-columns: 320px minmax(0, 1fr);
}

.pane,
.drawer-panel {
  min-height: 0;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.08)) 48%, transparent);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
  overflow: hidden;
}

.pane {
  display: flex;
  flex-direction: column;
}

.pane-head,
.drawer-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.08)) 48%, transparent);
}

.pane-head strong,
.drawer-head strong,
.card-title {
  color: var(--primary, #00ffc8);
  font-size: 12px;
  font-weight: 700;
}

.pane-head small,
.muted,
.card-meta,
.info-line span,
.form-block span {
  color: var(--text-dim, #8fa5b3);
  font-size: 11px;
}

.tree-scroll,
.list-scroll,
.detail-scroll,
.drawer-list,
.drawer-detail {
  min-height: 0;
  overflow: auto;
  padding: 12px;
}

.detail-scroll,
.drawer-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tree-node,
.list-card {
  width: 100%;
  text-align: left;
  border-radius: 10px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.08)) 48%, transparent);
  background: rgba(255, 255, 255, 0.03);
  color: var(--text-main, #e8fff8);
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}

.tree-node {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}

.list-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  margin-bottom: 8px;
}

.tree-node.active,
.list-card.selected {
  border-color: color-mix(in oklab, var(--primary, #00ffc8) 42%, transparent);
  box-shadow: 0 0 0 1px color-mix(in oklab, var(--primary, #00ffc8) 20%, transparent);
  background: linear-gradient(
    90deg,
    color-mix(in oklab, var(--primary, #00ffc8) 12%, transparent),
    transparent
  );
}

.tree-node:hover,
.list-card:hover,
.btn-primary:hover,
.btn-secondary:hover,
.mini-btn:hover {
  transform: translateY(-1px);
}

.card-top,
.info-line,
.diff-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.card-desc {
  margin: 0;
  color: var(--text-dim, #b3c5cf);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.info-card {
  margin: 0 12px 12px;
  padding: 12px;
  border-radius: 10px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.08)) 48%, transparent);
  background: rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-line strong {
  color: var(--text-main, #eefaf5);
  font-size: 12px;
  font-weight: 600;
}

.filters-grid,
.form-grid {
  display: grid;
  gap: 8px;
}

.filters-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 12px 12px 0;
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.form-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.form-grid.four {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.form-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field {
  width: 100%;
  background: color-mix(in oklab, black 72%, transparent);
  border: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.12)) 68%, transparent);
  color: var(--text-main, #fff);
  border-radius: 8px;
  padding: 9px 11px;
  font-size: 12px;
  box-sizing: border-box;
}

.field:focus {
  outline: none;
  border-color: var(--primary, #00ffc8);
  box-shadow: 0 0 0 1px color-mix(in oklab, var(--primary, #00ffc8) 24%, transparent);
}

.code-input {
  min-height: 96px;
  resize: vertical;
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  line-height: 1.55;
}

.code-input.tall {
  min-height: 150px;
}

.inline-check {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-main, #eefaf5);
  font-size: 12px;
}

.inline-check.compact {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.08)) 48%, transparent);
  background: rgba(255, 255, 255, 0.03);
}

.inline-check input {
  accent-color: var(--primary, #00ffc8);
}

.list-actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  padding: 12px;
}

.pager-mini {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-dim, #8fa5b3);
  font-size: 12px;
}

.btn-primary,
.btn-secondary,
.mini-btn {
  border-radius: 8px;
  padding: 8px 12px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255, 255, 255, 0.14)) 68%, transparent);
  cursor: pointer;
  font-size: 12px;
}

.btn-primary {
  background: var(--primary, #00ffc8);
  color: #000;
  border-color: transparent;
  font-weight: 700;
}

.btn-secondary,
.mini-btn {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-main, #f2fbf7);
}

.btn-secondary.active {
  border-color: var(--primary, #00ffc8);
  color: var(--primary, #00ffc8);
}

.btn-secondary.danger,
.mini-btn.danger {
  color: #fca5a5;
  border-color: rgba(239, 68, 68, 0.35);
}

.mini-btn {
  padding: 6px 10px;
  font-size: 11px;
}

.drawer-panel {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  min-height: 280px;
  max-height: 360px;
}

.drawer-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 10px;
  padding: 12px;
}

.drawer-list {
  padding: 0;
}

.drawer-detail {
  padding: 0;
}

.preview-grid {
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
}

.toolbar-toast {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  border: 1px solid;
}

.toolbar-toast.info {
  background: rgba(59, 130, 246, 0.14);
  border-color: rgba(59, 130, 246, 0.4);
  color: #93c5fd;
}

.toolbar-toast.success {
  background: rgba(34, 197, 94, 0.14);
  border-color: rgba(34, 197, 94, 0.4);
  color: #86efac;
}

.toolbar-toast.error {
  background: rgba(239, 68, 68, 0.14);
  border-color: rgba(239, 68, 68, 0.4);
  color: #fca5a5;
}

.empty-state {
  padding: 24px 14px;
  text-align: center;
  color: var(--text-dim, #8ea3b2);
  font-size: 12px;
}

.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.tree-scroll::-webkit-scrollbar,
.list-scroll::-webkit-scrollbar,
.detail-scroll::-webkit-scrollbar,
.drawer-list::-webkit-scrollbar,
.drawer-detail::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.tree-scroll::-webkit-scrollbar-thumb,
.list-scroll::-webkit-scrollbar-thumb,
.detail-scroll::-webkit-scrollbar-thumb,
.drawer-list::-webkit-scrollbar-thumb,
.drawer-detail::-webkit-scrollbar-thumb {
  background: linear-gradient(
    180deg,
    color-mix(in oklab, var(--primary, #00ffc8) 45%, transparent),
    color-mix(in oklab, var(--primary-2, #00aaff) 45%, transparent)
  );
  border-radius: 999px;
}

@media (max-width: 1380px) {
  .main-grid {
    grid-template-columns: 220px minmax(280px, 0.9fr) minmax(0, 1.1fr);
  }

  .filters-grid,
  .form-grid.four {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1160px) {
  .main-grid,
  .policy-grid,
  .drawer-grid,
  .preview-grid {
    grid-template-columns: 1fr;
  }

  .filters-grid,
  .form-grid.two,
  .form-grid.three,
  .form-grid.four {
    grid-template-columns: 1fr;
  }

  .list-actions,
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .pager-mini {
    justify-content: space-between;
  }
}
</style>
