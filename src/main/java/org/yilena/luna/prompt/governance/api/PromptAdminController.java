package org.yilena.luna.prompt.governance.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptFrontendAdapter;
import org.yilena.luna.prompt.governance.PromptMutationService;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptPreviewService;
import org.yilena.luna.prompt.governance.PromptQueryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.dto.PromptPolicyVersionSwitchRequest;
import org.yilena.luna.prompt.governance.dto.PromptPreviewRequest;
import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.dto.PromptVersionDiffRequest;
import org.yilena.luna.prompt.governance.dto.PromptVersionSwitchRequest;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.model.PromptCategoryTreeNode;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prompt")
@RequiredArgsConstructor
@Tag(name = "提示词治理接口", description = "用于提示词分类、查询、变更、版本管理、预览和策略包维护")
/**
 * 提示词治理控制器，负责对外暴露提示词分类、条目、版本、预览和策略包管理接口，
 * 为治理后台提供统一的管理入口。
 */
public class PromptAdminController {

    private final PromptQueryService promptQueryService;
    private final PromptMutationService promptMutationService;
    private final PromptVersionService promptVersionService;
    private final PromptPreviewService promptPreviewService;
    private final PromptFrontendAdapter promptFrontendAdapter;
    private final PromptPolicyService promptPolicyService;
    private final PromptRegistryService promptRegistryService;
    private final PromptCategoryService promptCategoryService;

    /**
     * 查询所有提示词分类键列表，供前端初始化分类筛选项使用。
     */
    @GetMapping("/categories")
    @Operation(summary = "查询提示词分类列表", description = "返回当前系统可用的提示词分类键列表，适用于分类筛选和下拉展示场景")
    public ResponseEntity<?> categories() {
        return ResponseEntity.ok(promptQueryService.listCategories());
    }

    /**
     * 查询启用中的分类明细，返回适合后台列表页直接渲染的分类详情结构。
     */
    @GetMapping("/categories/detail")
    @Operation(summary = "查询提示词分类明细", description = "返回已启用分类的名称、排序、关键字匹配能力和执行类标记，适用于治理后台分类管理页")
    public ResponseEntity<?> categoryDetails() {
        List<Map<String, Object>> payload = promptCategoryService.listEnabledOrdered().stream()
                .map(this::toCategoryDetail)
                .toList();
        return ResponseEntity.ok(payload);
    }

    /**
     * 查询启用分类的树形结构，便于前端按层级展示分类关系。
     */
    @GetMapping("/categories/tree")
    @Operation(summary = "查询提示词分类树", description = "按父子层级返回启用中的提示词分类树，适用于树形选择器和分类导航场景")
    public ResponseEntity<?> categoryTree() {
        List<Map<String, Object>> payload = promptCategoryService.listEnabledTree().stream()
                .map(this::toCategoryTreeNode)
                .toList();
        return ResponseEntity.ok(payload);
    }

    /**
     * 按分类查询提示词键值视图，支持二级分类过滤，便于前端直接展示分类下的提示词条目。
     */
    @GetMapping("/items")
    @Operation(summary = "按分类查询提示词键值列表", description = "根据一级分类和可选二级分类返回提示词键值视图，适用于分类浏览和快捷选择场景")
    public ResponseEntity<?> items(@RequestParam String category,
                                   @RequestParam(required = false) String subCategory) {
        return ResponseEntity.ok(promptFrontendAdapter.toCategoryKeyValueView(
                category,
                subCategory,
                promptQueryService.search(buildCategoryRequest(category, subCategory))
        ));
    }

    /**
     * 根据提示词键查询详情，允许读取已禁用条目，便于治理后台查看完整配置。
     */
    @GetMapping("/item/detail")
    @Operation(summary = "按键查询提示词详情", description = "根据提示词唯一键返回完整详情，包含已禁用条目，适用于详情查看和编辑回填场景")
    public ResponseEntity<?> detail(@RequestParam String key) {
        Object payload = promptRegistryService.getByKeyIncludingDisabled(key).<Object>map(item -> item).orElse(Map.of());
        return ResponseEntity.ok(payload);
    }

    /**
     * 根据提示词主键查询详情，允许读取已禁用条目，便于版本和明细联动场景使用。
     */
    @GetMapping("/item/detail-by-id")
    @Operation(summary = "按主键查询提示词详情", description = "根据提示词主键返回完整详情，适用于版本列表跳转详情等需要按主键反查的场景")
    public ResponseEntity<?> detailById(@RequestParam Long id) {
        Object payload = promptRegistryService.getByIdIncludingDisabled(id).<Object>map(item -> item).orElse(Map.of());
        return ResponseEntity.ok(payload);
    }

    /**
     * 校验指定提示词键是否已存在，便于创建前做唯一性校验。
     */
    @GetMapping("/item/exists")
    @Operation(summary = "校验提示词键是否存在", description = "根据提示词唯一键返回是否已存在，适用于创建前校验和前端实时校验场景")
    public ResponseEntity<?> exists(@RequestParam String key) {
        return ResponseEntity.ok(Map.of("key", key, "exists", promptRegistryService.existsByKey(key)));
    }

    /**
     * 查询指定分类下的提示词键值映射，适合轻量级联动选择场景。
     */
    @GetMapping("/item/key-values")
    @Operation(summary = "查询分类下提示词键值映射", description = "根据分类返回提示词键和值的映射结果，适用于下拉选择和轻量展示场景")
    public ResponseEntity<?> keyValues(@RequestParam String category) {
        return ResponseEntity.ok(promptRegistryService.listKeyValueByCategory(category));
    }

    /**
     * 按查询条件检索提示词，支持分类、关键字、启用状态和分页等过滤条件。
     */
    @PostMapping("/search")
    @Operation(summary = "检索提示词列表", description = "根据分类、关键字、匹配能力和分页条件检索提示词，适用于治理后台列表查询场景")
    public ResponseEntity<?> search(@RequestBody(required = false) PromptSearchRequest request) {
        return ResponseEntity.ok(promptQueryService.search(request));
    }

    /**
     * 创建新的内容提示词条目，适用于治理后台新增提示词场景。
     */
    @PostMapping("/item/create")
    @Operation(summary = "创建提示词", description = "根据提交的提示词内容、分类和匹配规则创建新的提示词条目")
    public ResponseEntity<?> create(@RequestBody PromptUpsertRequest request) {
        return ResponseEntity.ok(promptMutationService.create(request));
    }

    /**
     * 更新已有提示词条目，适用于治理后台编辑保存场景。
     */
    @PostMapping("/item/update")
    @Operation(summary = "更新提示词", description = "根据提交的提示词内容、匹配规则和版本说明更新已有提示词")
    public ResponseEntity<?> update(@RequestBody PromptUpsertRequest request) {
        return ResponseEntity.ok(promptMutationService.update(request));
    }

    /**
     * 保存提示词时先按键判断是否已存在，再自动走创建或更新分支，兼容前端统一保存入口。
     */
    @PostMapping("/item/save")
    @Operation(summary = "保存提示词", description = "根据提示词键自动判断执行创建还是更新，适用于前端统一保存按钮场景")
    public ResponseEntity<?> save(@RequestBody PromptUpsertRequest request) {
        String key = request == null ? "" : request.getKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        boolean exists = promptRegistryService.existsByKey(key);
        return ResponseEntity.ok(exists ? promptMutationService.update(request) : promptMutationService.create(request));
    }

    /**
     * 按提示词键删除条目，适用于治理后台执行显式删除操作。
     */
    @PostMapping("/item/delete")
    @Operation(summary = "删除提示词", description = "根据提示词键删除对应条目，适用于治理后台明确删除场景")
    public ResponseEntity<?> delete(@RequestBody Map<String, String> request) {
        String key = request == null ? "" : request.getOrDefault("key", "");
        promptMutationService.deleteByKey(key);
        return ResponseEntity.ok(Map.of("success", true, "key", key));
    }

    /**
     * 查询指定提示词键的版本列表，便于治理后台展示版本历史。
     */
    @GetMapping("/item/versions")
    @Operation(summary = "查询提示词版本列表", description = "根据提示词键返回版本历史列表，适用于版本管理和回滚选择场景")
    public ResponseEntity<?> versions(@RequestParam String key) {
        return ResponseEntity.ok(promptVersionService.listVersions(key));
    }

    /**
     * 查询单个提示词版本详情，便于治理后台查看具体版本内容。
     */
    @GetMapping("/item/version/detail")
    @Operation(summary = "查询提示词版本详情", description = "根据版本标识返回单个提示词版本详情，适用于版本对比前查看场景")
    public ResponseEntity<?> versionDetail(@RequestParam Long versionId) {
        Object payload = promptVersionService.getVersionDetail(versionId);
        return ResponseEntity.ok(payload == null ? Map.of() : payload);
    }

    /**
     * 激活指定提示词版本，使其成为当前运行时默认版本。
     */
    @PostMapping("/item/activate")
    @Operation(summary = "激活提示词版本", description = "根据版本标识激活目标版本，使其成为当前提示词的生效版本")
    public ResponseEntity<?> activate(@RequestBody PromptVersionSwitchRequest request) {
        promptVersionService.activateVersion(request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "versionId", request.getVersionId()));
    }

    /**
     * 将指定提示词键回滚到历史版本，适用于错误配置回退场景。
     */
    @PostMapping("/item/rollback")
    @Operation(summary = "回滚提示词版本", description = "根据提示词键和目标版本执行版本回滚，适用于错误配置恢复场景")
    public ResponseEntity<?> rollback(@RequestBody PromptVersionSwitchRequest request) {
        promptVersionService.rollbackToVersion(request.getKey(), request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "versionId", request.getVersionId(), "key", request.getKey()));
    }

    /**
     * 保存一份提示词草稿版本，用于尚未正式激活前的草稿留存。
     */
    @PostMapping("/item/draft")
    @Operation(summary = "保存提示词草稿版本", description = "根据当前提示词内容生成草稿版本，适用于治理过程中的暂存场景")
    public ResponseEntity<?> draft(@RequestBody PromptUpsertRequest request) {
        String key = request == null ? "" : request.getKey();
        return ResponseEntity.ok(promptVersionService.saveDraft(key, request));
    }

    /**
     * 归档指定提示词版本，防止其继续作为活跃候选版本参与治理流程。
     */
    @PostMapping("/item/archive")
    @Operation(summary = "归档提示词版本", description = "根据版本标识归档目标版本，适用于淘汰旧版本或冻结版本场景")
    public ResponseEntity<?> archive(@RequestBody PromptVersionSwitchRequest request) {
        promptVersionService.archiveVersion(request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "versionId", request.getVersionId()));
    }

    /**
     * 对比两个提示词版本差异，请求体为空时直接拒绝，避免无意义比对。
     */
    @PostMapping("/item/diff")
    @Operation(summary = "对比提示词版本差异", description = "根据左右两个版本标识返回差异结果，适用于治理后台版本对比场景")
    public ResponseEntity<?> diff(@RequestBody PromptVersionDiffRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return ResponseEntity.ok(promptVersionService.diff(request.getLeftVersionId(), request.getRightVersionId()));
    }

    /**
     * 预览提示词匹配结果，按提交的解析上下文模拟实际运行时命中过程。
     */
    @PostMapping("/preview/match")
    @Operation(summary = "预览提示词匹配结果", description = "根据模拟的解析上下文返回命中和拒绝结果，适用于治理后台调试匹配规则")
    public ResponseEntity<?> previewMatch(@RequestBody PromptPreviewRequest request) {
        return ResponseEntity.ok(promptPreviewService.previewMatch(toResolveContext(request)));
    }

    /**
     * 预览提示词组装结果，按提交上下文模拟运行时各槽位的最终组装内容。
     */
    @PostMapping("/preview/assemble")
    @Operation(summary = "预览提示词组装结果", description = "根据模拟上下文返回提示词解析后各槽位的组装结果，适用于治理后台调试最终提示结构")
    public ResponseEntity<?> previewAssemble(@RequestBody PromptPreviewRequest request) {
        return ResponseEntity.ok(promptPreviewService.previewAssemble(toResolveContext(request)));
    }

    /**
     * 查询全部策略包列表，供策略管理页展示当前策略集合。
     */
    @GetMapping("/policy/list")
    @Operation(summary = "查询策略包列表", description = "返回系统中的提示策略包列表，适用于治理后台策略管理页")
    public ResponseEntity<?> policies() {
        return ResponseEntity.ok(promptPolicyService.listPolicies());
    }

    /**
     * 根据策略标识查询策略包详情，便于前端回显当前生效版本和包含排除配置。
     */
    @GetMapping("/policy/detail")
    @Operation(summary = "查询策略包详情", description = "根据策略标识返回策略包详情，包含当前版本和包含排除项信息")
    public ResponseEntity<?> policyDetail(@RequestParam String policyId) {
        Object payload = promptPolicyService.getPolicyDetail(policyId);
        return ResponseEntity.ok(payload == null ? Map.of() : payload);
    }

    /**
     * 保存策略包配置，适用于新增策略包或更新策略包场景。
     */
    @PostMapping("/policy/save")
    @Operation(summary = "保存策略包", description = "根据提交的策略信息保存策略包及其版本内容，适用于治理后台策略保存场景")
    public ResponseEntity<?> savePolicy(@RequestBody PromptPolicySaveRequest request) {
        return ResponseEntity.ok(promptPolicyService.savePolicy(request));
    }

    /**
     * 删除指定策略包，适用于治理后台移除无效策略场景。
     */
    @PostMapping("/policy/delete")
    @Operation(summary = "删除策略包", description = "根据策略标识删除对应策略包，适用于治理后台策略清理场景")
    public ResponseEntity<?> deletePolicy(@RequestBody Map<String, String> request) {
        String policyId = request == null ? "" : request.getOrDefault("policyId", "");
        promptPolicyService.deletePolicy(policyId);
        return ResponseEntity.ok(Map.of("success", true, "policyId", policyId));
    }

    /**
     * 查询指定策略包的版本列表，便于治理后台查看版本历史与切换版本。
     */
    @GetMapping("/policy/versions")
    @Operation(summary = "查询策略包版本列表", description = "根据策略标识返回版本历史列表，适用于策略版本管理场景")
    public ResponseEntity<?> policyVersions(@RequestParam String policyId) {
        return ResponseEntity.ok(promptPolicyService.listPolicyVersions(policyId));
    }

    /**
     * 激活指定策略包版本，使其成为当前运行时策略配置。
     */
    @PostMapping("/policy/activate")
    @Operation(summary = "激活策略包版本", description = "根据策略标识和版本标识激活目标版本，适用于策略切换场景")
    public ResponseEntity<?> activatePolicy(@RequestBody PromptPolicyVersionSwitchRequest request) {
        promptPolicyService.activatePolicyVersion(request.getPolicyId(), request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "policyId", request.getPolicyId(), "versionId", request.getVersionId()));
    }

    private PromptResolveContext toResolveContext(PromptPreviewRequest request) {
        PromptPreviewRequest req = request == null ? new PromptPreviewRequest() : request;
        return PromptResolveContext.builder()
                .sessionId(req.getSessionId())
                .userInput(req.getUserInput())
                .policyId(req.getPolicyId())
                .personaId(req.getPersonaId())
                .sceneId(req.getSceneId())
                .agent(req.getAgent())
                .nodeKind(req.getNodeKind())
                .taskState(req.getTaskState())
                .modelFamily(req.getModelFamily())
                .manualPromptKeys(req.getManualPromptKeys())
                .build();
    }

    private PromptSearchRequest buildCategoryRequest(String category, String subCategory) {
        PromptSearchRequest request = new PromptSearchRequest();
        request.setCategory(category);
        request.setSubCategory(subCategory);
        request.setEnabled(true);
        request.setPageNo(1L);
        request.setPageSize(500L);
        return request;
    }

    private Map<String, Object> toCategoryDetail(PromptCategoryEntity category) {
        if (category == null) {
            return Map.of();
        }
        return Map.of(
                "categoryKey", category.getCategoryKey() == null ? "" : category.getCategoryKey(),
                "categoryName", category.getCategoryName() == null ? "" : category.getCategoryName(),
                "sortOrder", category.getSortOrder() == null ? 0 : category.getSortOrder(),
                "keywordMatchAllowed", !Boolean.FALSE.equals(category.getKeywordMatchAllowed()),
                "executionCategory", Boolean.TRUE.equals(category.getIsExecutionCategory()),
                "enabled", !Boolean.FALSE.equals(category.getEnabled())
        );
    }

    private Map<String, Object> toCategoryTreeNode(PromptCategoryTreeNode node) {
        if (node == null) {
            return Map.of();
        }
        List<Map<String, Object>> children = node.getChildren() == null
                ? List.of()
                : node.getChildren().stream().map(this::toCategoryTreeNode).toList();
        return Map.of(
                "categoryKey", node.getCategoryKey() == null ? "" : node.getCategoryKey(),
                "categoryName", node.getCategoryName() == null ? "" : node.getCategoryName(),
                "parentCategoryKey", node.getParentCategoryKey() == null ? "" : node.getParentCategoryKey(),
                "sortOrder", node.getSortOrder() == null ? 0 : node.getSortOrder(),
                "keywordMatchAllowed", !Boolean.FALSE.equals(node.getKeywordMatchAllowed()),
                "executionCategory", Boolean.TRUE.equals(node.getExecutionCategory()),
                "enabled", !Boolean.FALSE.equals(node.getEnabled()),
                "children", children
        );
    }
}
