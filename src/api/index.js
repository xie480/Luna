export const startup = async () => window.desktopApi.startup();

export const chat = async (payload) => window.desktopApi.chatMessage(payload);

export const shutdown = async () => window.desktopApi.shutdown();

export const historyDate = async (payload) => window.desktopApi.historyDate(payload);

export const history = async (payload) => window.desktopApi.history(payload);

export const login = async (payload) => window.desktopApi.login(payload);

export const logout = async () => window.desktopApi.logout();

export const queryKnowledgeBase = async (payload) => window.desktopApi.queryKnowledgeBase(payload);

export const queryLog = async (payload) => window.desktopApi.queryLog(payload);

export const ragRetrieve = async (payload) => window.desktopApi.ragRetrieve(payload);

export const planRun = async (payload) => window.desktopApi.planRun(payload);

export const planPhaseRun = async (payload) => window.desktopApi.planPhaseRun(payload);

export const planFinalizeReport = async (payload) => window.desktopApi.planFinalizeReport(payload);

export const planGraph = async (planId) => window.desktopApi.planGraph(planId);

export const openExternal = async (target) => window.desktopApi.openExternal(target);

export const listMcpResources = async () => window.mcpApi.listResources();

export const getMcpResourceById = async (id) => window.mcpApi.getResourceById(id);

export const searchMcpResources = async (payload) => window.mcpApi.searchResources(payload);

export const listMcpTools = async (payload) => window.mcpApi.listTools(payload);

export const callMcpTool = async (payload) => window.mcpApi.callTool(payload);

export const listMcpPrompts = async (payload) => window.mcpApi.listPrompts(payload);

export const getMcpPrompt = async (payload) => window.mcpApi.getPrompt(payload);

export const listMcpCatalogResources = async (payload) => window.mcpApi.listCatalogResources(payload);

export const readMcpCatalogResource = async (payload) => window.mcpApi.readCatalogResource(payload);

export const syncMcpCatalog = async () => window.mcpApi.syncCatalog();

export const saveMcpServerRegistry = async (payload) => window.mcpApi.saveServerRegistry(payload);

export const saveMcpToolCatalog = async (payload) => window.mcpApi.saveToolCatalog(payload);

export const saveMcpToolImplMapping = async (payload) => window.mcpApi.saveToolImplMapping(payload);

export const saveMcpPromptCatalog = async (payload) => window.mcpApi.savePromptCatalog(payload);

export const saveMcpResourceCatalog = async (payload) => window.mcpApi.saveResourceCatalog(payload);

export const saveWorkflowTemplate = async (payload) => window.mcpApi.saveWorkflowTemplate(payload);

export const callMcpRpc = async (payload) => window.mcpApi.callRpc(payload);

export const approveTool = async (payload) => window.mcpApi.approveTool(payload);

export const listPromptCategories = async () => window.promptApi.listCategories();

export const listPromptCategoryDetails = async () => window.promptApi.listCategoryDetails();

export const getPromptCategoryTree = async () => window.promptApi.getCategoryTree();

export const listPromptItemsByCategory = async (payload) => window.promptApi.listItemsByCategory(payload);

export const getPromptItemDetail = async (payload) => window.promptApi.getItemDetail(payload);

export const getPromptItemDetailById = async (payload) => window.promptApi.getItemDetailById(payload);

export const checkPromptItemExists = async (payload) => window.promptApi.checkItemExists(payload);

export const getPromptItemKeyValues = async (payload) => window.promptApi.getItemKeyValues(payload);

export const searchPromptItems = async (payload) => window.promptApi.searchItems(payload);

export const createPromptItem = async (payload) => window.promptApi.createItem(payload);

export const updatePromptItem = async (payload) => window.promptApi.updateItem(payload);

export const savePromptItem = async (payload) => window.promptApi.saveItem(payload);

export const deletePromptItem = async (payload) => window.promptApi.deleteItem(payload);

export const listPromptVersions = async (payload) => window.promptApi.listVersions(payload);

export const getPromptVersionDetail = async (payload) => window.promptApi.getVersionDetail(payload);

export const activatePromptVersion = async (payload) => window.promptApi.activateVersion(payload);

export const rollbackPromptVersion = async (payload) => window.promptApi.rollbackVersion(payload);

export const savePromptDraft = async (payload) => window.promptApi.saveDraft(payload);

export const archivePromptVersion = async (payload) => window.promptApi.archiveVersion(payload);

export const diffPromptVersions = async (payload) => window.promptApi.diffVersions(payload);

export const previewPromptMatch = async (payload) => window.promptApi.previewMatch(payload);

export const previewPromptAssemble = async (payload) => window.promptApi.previewAssemble(payload);

export const listPromptPolicies = async () => window.promptApi.listPolicies();

export const getPromptPolicyDetail = async (payload) => window.promptApi.getPolicyDetail(payload);

export const savePromptPolicy = async (payload) => window.promptApi.savePolicy(payload);

export const deletePromptPolicy = async (payload) => window.promptApi.deletePolicy(payload);

export const listPromptPolicyVersions = async (payload) => window.promptApi.listPolicyVersions(payload);

export const activatePromptPolicyVersion = async (payload) => window.promptApi.activatePolicyVersion(payload);
