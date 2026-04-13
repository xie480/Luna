function isPlainObject(value) {
  return Object.prototype.toString.call(value) === "[object Object]";
}

export function ensureArray(value) {
  if (Array.isArray(value)) return value;
  if (value === null || value === undefined || value === "") return [];
  return [value];
}

export function ensureObject(value) {
  if (isPlainObject(value)) return value;
  return {};
}

export function safeJsonParse(value, fallback = null) {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "object") return value;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

export function stringifyPretty(value, fallback = "") {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "string") {
    const parsed = safeJsonParse(value, undefined);
    if (parsed !== undefined) {
      try {
        return JSON.stringify(parsed, null, 2);
      } catch {
        return value;
      }
    }
    return value;
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export function normalizeJsonField(value) {
  if (value === null || value === undefined || value === "") return "";
  if (typeof value === "string") return value;
  return stringifyPretty(value, "");
}

export function parseTextArray(value) {
  const text = String(value || "").trim();
  if (!text) return [];

  if (text.startsWith("[")) {
    const parsed = safeJsonParse(text, []);
    return ensureArray(parsed).map((item) => String(item).trim()).filter(Boolean);
  }

  return text
    .split(/[\n,]/g)
    .map((item) => item.trim())
    .filter(Boolean);
}

export function parseJsonInput(value, fallback = {}) {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "object") return value;
  return safeJsonParse(value, fallback);
}

export function normalizeBooleanish(value, defaultValue = false) {
  if (typeof value === "boolean") return value;
  if (typeof value === "number") return value !== 0;
  const text = String(value || "").trim().toLowerCase();
  if (!text) return defaultValue;
  if (["true", "1", "yes", "y", "on"].includes(text)) return true;
  if (["false", "0", "no", "n", "off"].includes(text)) return false;
  return defaultValue;
}

export function pickTextMessage(value) {
  if (typeof value === "string") return value;
  if (!value || typeof value !== "object") return "";
  return (
    value.reply ||
    value.message ||
    value.text ||
    value.content ||
    value.answer ||
    value.raw ||
    value.rawResult ||
    value.error ||
    value.reason ||
    ""
  );
}

export function getApprovalDisplayName(task) {
  return task?.toolName || task?.skillName || "-";
}

export function normalizeApprovalTask(task) {
  const data = task?.payload ? task.payload : task;
  const argsRaw = data?.argsJson ?? data?.argumentsJson ?? "";
  return {
    ...ensureObject(data),
    displayName: getApprovalDisplayName(data),
    argsJson: typeof argsRaw === "string" ? argsRaw : stringifyPretty(argsRaw, "{}"),
  };
}

export function normalizeResource(resource) {
  const data = ensureObject(resource);
  return {
    ...data,
    id: data.id ?? "",
    type: data.type || "",
    name: data.name || data.toolName || data.promptName || data.resourceUri || "",
    serverCode: data.serverCode || "",
    description: data.description || "",
    version: data.version || "",
    executionMode: data.executionMode || "",
    runMode: data.runMode || "",
    requiresApproval: normalizeBooleanish(data.requiresApproval, false),
    sensitivity: data.sensitivity || "",
    inputSchema: normalizeJsonField(data.inputSchema),
    outputSchema: normalizeJsonField(data.outputSchema),
    argumentsSchema: normalizeJsonField(data.argumentsSchema),
    requiredCapabilities: ensureArray(data.requiredCapabilities).map((item) => String(item)),
    toolSlots: ensureArray(data.toolSlots),
    thoughtChain: ensureArray(data.thoughtChain).map((item) => String(item)),
    annotations: data.annotations ?? safeJsonParse(data.annotations, {}),
    tags: ensureArray(data.tags).map((item) => String(item)),
  };
}

export function normalizePromptItem(item) {
  const data = ensureObject(item);
  return {
    ...data,
    itemId: data.itemId ?? data.id ?? "",
    versionId: data.versionId ?? "",
    key: data.key || "",
    name: data.name || data.promptName || data.key || "",
    value: data.value || "",
    category: data.category || data.categoryKey || "",
    subCategory: data.subCategory || "",
    description: data.description || "",
    runtimeSlot: data.runtimeSlot || "",
    hasTemplateVariables: normalizeBooleanish(data.hasTemplateVariables, false),
    templateVariables: ensureArray(data.templateVariables).map((item2) => String(item2)),
    keywordMatchEnabled: normalizeBooleanish(data.keywordMatchEnabled, false),
    matchKeywords: ensureArray(data.matchKeywords).map((item2) => String(item2)),
    assemblyMode: data.assemblyMode || "",
    matchScope: {
      agents: ensureArray(data.matchScope?.agents).map((item2) => String(item2)),
      nodeKinds: ensureArray(data.matchScope?.nodeKinds).map((item2) => String(item2)),
      taskStates: ensureArray(data.matchScope?.taskStates).map((item2) => String(item2)),
      modelFamilies: ensureArray(data.matchScope?.modelFamilies).map((item2) => String(item2)),
      personaIds: ensureArray(data.matchScope?.personaIds).map((item2) => String(item2)),
      sceneIds: ensureArray(data.matchScope?.sceneIds).map((item2) => String(item2)),
    },
    editPolicy: {
      create: normalizeBooleanish(data.editPolicy?.create, true),
      update: normalizeBooleanish(data.editPolicy?.update, true),
      delete: normalizeBooleanish(data.editPolicy?.delete, true),
    },
    enabled: normalizeBooleanish(data.enabled, true),
    priority: data.priority ?? 0,
    status: data.status || "",
    version: data.version || "",
    versionLabel: data.versionLabel || "",
    changeNote: data.changeNote || "",
  };
}

export function normalizePromptVersion(version) {
  const data = ensureObject(version);
  return {
    ...data,
    id: data.id ?? "",
    promptItemId: data.promptItemId ?? "",
    versionNo: data.versionNo || data.version || "",
    versionLabel: data.versionLabel || "",
    promptValue: data.promptValue || data.value || "",
    templateVariables: ensureArray(data.templateVariables).map((item) => String(item)),
    matchKeywords: ensureArray(data.matchKeywords).map((item) => String(item)),
    matchScope: {
      agents: ensureArray(data.matchScope?.agents).map((item) => String(item)),
      nodeKinds: ensureArray(data.matchScope?.nodeKinds).map((item) => String(item)),
      taskStates: ensureArray(data.matchScope?.taskStates).map((item) => String(item)),
      modelFamilies: ensureArray(data.matchScope?.modelFamilies).map((item) => String(item)),
      personaIds: ensureArray(data.matchScope?.personaIds).map((item) => String(item)),
      sceneIds: ensureArray(data.matchScope?.sceneIds).map((item) => String(item)),
    },
    editPolicy: {
      create: normalizeBooleanish(data.editPolicy?.create, true),
      update: normalizeBooleanish(data.editPolicy?.update, true),
      delete: normalizeBooleanish(data.editPolicy?.delete, true),
    },
    status: data.status || "",
    changeNote: data.changeNote || "",
    isActive: normalizeBooleanish(data.isActive, false),
    createdAt: data.createdAt || "",
    updatedAt: data.updatedAt || "",
  };
}

export function normalizePromptPolicy(policy) {
  const data = ensureObject(policy);
  return {
    ...data,
    id: data.id ?? "",
    policyId: data.policyId || data.policyKey || "",
    policyKey: data.policyKey || data.policyId || "",
    policyName: data.policyName || data.policyId || data.policyKey || "",
    description: data.description || "",
    enabled: normalizeBooleanish(data.enabled, true),
    currentVersionId: data.currentVersionId ?? "",
    currentVersionNo: data.currentVersionNo || "",
    includePromptKeys: ensureArray(data.includePromptKeys).map((item) => String(item)),
    excludePromptKeys: ensureArray(data.excludePromptKeys).map((item) => String(item)),
    createdAt: data.createdAt || "",
    updatedAt: data.updatedAt || "",
  };
}

export function normalizePromptPolicyVersion(version) {
  const data = ensureObject(version);
  return {
    ...data,
    id: data.id ?? "",
    promptPolicyId: data.promptPolicyId ?? "",
    versionNo: data.versionNo || data.version || "",
    includePromptKeys: ensureArray(data.includePromptKeys).map((item) => String(item)),
    excludePromptKeys: ensureArray(data.excludePromptKeys).map((item) => String(item)),
    status: data.status || "",
    changeNote: data.changeNote || "",
    isActive: normalizeBooleanish(data.isActive, false),
    createdAt: data.createdAt || "",
    updatedAt: data.updatedAt || "",
  };
}

export function normalizeRagResponse(response) {
  const data = ensureObject(response?.data ?? response);
  const evidences = ensureObject(data.evidences);
  return {
    ...data,
    route: data.route || "",
    rewrittenQuery: data.rewrittenQuery || "",
    evidences: {
      knowledge: ensureArray(evidences.knowledge),
      memory: ensureArray(evidences.memory),
      preference: ensureArray(evidences.preference),
    },
    evidenceRoleGroups: ensureObject(data.evidenceRoleGroups),
    meta: ensureObject(data.meta),
  };
}

export function toJsonPayload(text, fallback = undefined) {
  const raw = String(text || "").trim();
  if (!raw) return fallback;
  const parsed = safeJsonParse(raw, undefined);
  if (parsed === undefined) {
    throw new Error("JSON 格式不合法");
  }
  return parsed;
}

export function toJsonString(text, fallback = "") {
  const parsed = toJsonPayload(text, undefined);
  if (parsed === undefined) return fallback;
  return JSON.stringify(parsed);
}

export function isNumericId(value) {
  return /^[0-9]+$/.test(String(value || "").trim());
}
