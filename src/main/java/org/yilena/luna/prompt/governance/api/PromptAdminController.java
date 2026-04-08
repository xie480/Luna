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
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prompt")
@RequiredArgsConstructor
@Tag(name = "Prompt Admin API", description = "Prompt registry/query/mutation/version/preview")
public class PromptAdminController {

    private final PromptQueryService promptQueryService;
    private final PromptMutationService promptMutationService;
    private final PromptVersionService promptVersionService;
    private final PromptPreviewService promptPreviewService;
    private final PromptFrontendAdapter promptFrontendAdapter;
    private final PromptPolicyService promptPolicyService;
    private final PromptRegistryService promptRegistryService;
    private final PromptCategoryService promptCategoryService;

    @GetMapping("/categories")
    @Operation(summary = "List prompt categories")
    public ResponseEntity<?> categories() {
        return ResponseEntity.ok(promptQueryService.listCategories());
    }

    @GetMapping("/categories/detail")
    @Operation(summary = "List prompt category details")
    public ResponseEntity<?> categoryDetails() {
        List<Map<String, Object>> payload = promptCategoryService.listEnabledOrdered().stream()
                .map(this::toCategoryDetail)
                .toList();
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/items")
    @Operation(summary = "List key/value items by category")
    public ResponseEntity<?> items(@RequestParam String category,
                                   @RequestParam(required = false) String subCategory) {
        return ResponseEntity.ok(promptFrontendAdapter.toCategoryKeyValueView(
                category,
                subCategory,
                promptQueryService.search(buildCategoryRequest(category, subCategory))
        ));
    }

    @GetMapping("/item/detail")
    @Operation(summary = "Get prompt detail by key")
    public ResponseEntity<?> detail(@RequestParam String key) {
        Object payload = promptRegistryService.getByKeyIncludingDisabled(key).<Object>map(item -> item).orElse(Map.of());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/item/detail-by-id")
    @Operation(summary = "Get prompt detail by id")
    public ResponseEntity<?> detailById(@RequestParam Long id) {
        Object payload = promptRegistryService.getByIdIncludingDisabled(id).<Object>map(item -> item).orElse(Map.of());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/item/exists")
    @Operation(summary = "Check prompt exists by key")
    public ResponseEntity<?> exists(@RequestParam String key) {
        return ResponseEntity.ok(Map.of("key", key, "exists", promptRegistryService.existsByKey(key)));
    }

    @GetMapping("/item/key-values")
    @Operation(summary = "List prompt key/value by category")
    public ResponseEntity<?> keyValues(@RequestParam String category) {
        return ResponseEntity.ok(promptRegistryService.listKeyValueByCategory(category));
    }

    @PostMapping("/search")
    @Operation(summary = "Search prompts")
    public ResponseEntity<?> search(@RequestBody(required = false) PromptSearchRequest request) {
        return ResponseEntity.ok(promptQueryService.search(request));
    }

    @PostMapping("/item/create")
    @Operation(summary = "Create content prompt")
    public ResponseEntity<?> create(@RequestBody PromptUpsertRequest request) {
        return ResponseEntity.ok(promptMutationService.create(request));
    }

    @PostMapping("/item/update")
    @Operation(summary = "Update prompt")
    public ResponseEntity<?> update(@RequestBody PromptUpsertRequest request) {
        return ResponseEntity.ok(promptMutationService.update(request));
    }

    @PostMapping("/item/save")
    @Operation(summary = "Save prompt (compatible alias for create/update)")
    public ResponseEntity<?> save(@RequestBody PromptUpsertRequest request) {
        String key = request == null ? "" : request.getKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        boolean exists = promptRegistryService.existsByKey(key);
        return ResponseEntity.ok(exists ? promptMutationService.update(request) : promptMutationService.create(request));
    }

    @PostMapping("/item/delete")
    @Operation(summary = "Delete content prompt by key")
    public ResponseEntity<?> delete(@RequestBody Map<String, String> request) {
        String key = request == null ? "" : request.getOrDefault("key", "");
        promptMutationService.deleteByKey(key);
        return ResponseEntity.ok(Map.of("success", true, "key", key));
    }

    @GetMapping("/item/versions")
    @Operation(summary = "List prompt versions by key")
    public ResponseEntity<?> versions(@RequestParam String key) {
        return ResponseEntity.ok(promptVersionService.listVersions(key));
    }

    @GetMapping("/item/version/detail")
    @Operation(summary = "Get prompt version detail by versionId")
    public ResponseEntity<?> versionDetail(@RequestParam Long versionId) {
        Object payload = promptVersionService.getVersionDetail(versionId);
        return ResponseEntity.ok(payload == null ? Map.of() : payload);
    }

    @PostMapping("/item/activate")
    @Operation(summary = "Activate prompt version")
    public ResponseEntity<?> activate(@RequestBody PromptVersionSwitchRequest request) {
        promptVersionService.activateVersion(request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "versionId", request.getVersionId()));
    }

    @PostMapping("/item/rollback")
    @Operation(summary = "Rollback prompt key to one version")
    public ResponseEntity<?> rollback(@RequestBody PromptVersionSwitchRequest request) {
        promptVersionService.rollbackToVersion(request.getKey(), request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "versionId", request.getVersionId(), "key", request.getKey()));
    }

    @PostMapping("/item/draft")
    @Operation(summary = "Save one draft prompt version")
    public ResponseEntity<?> draft(@RequestBody PromptUpsertRequest request) {
        String key = request == null ? "" : request.getKey();
        return ResponseEntity.ok(promptVersionService.saveDraft(key, request));
    }

    @PostMapping("/item/archive")
    @Operation(summary = "Archive one prompt version")
    public ResponseEntity<?> archive(@RequestBody PromptVersionSwitchRequest request) {
        promptVersionService.archiveVersion(request.getVersionId());
        return ResponseEntity.ok(Map.of("success", true, "versionId", request.getVersionId()));
    }

    @PostMapping("/item/diff")
    @Operation(summary = "Diff two prompt versions")
    public ResponseEntity<?> diff(@RequestBody PromptVersionDiffRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return ResponseEntity.ok(promptVersionService.diff(request.getLeftVersionId(), request.getRightVersionId()));
    }

    @PostMapping("/preview/match")
    @Operation(summary = "Preview prompt match result")
    public ResponseEntity<?> previewMatch(@RequestBody PromptPreviewRequest request) {
        return ResponseEntity.ok(promptPreviewService.previewMatch(toResolveContext(request)));
    }

    @PostMapping("/preview/assemble")
    @Operation(summary = "Preview prompt assemble result")
    public ResponseEntity<?> previewAssemble(@RequestBody PromptPreviewRequest request) {
        return ResponseEntity.ok(promptPreviewService.previewAssemble(toResolveContext(request)));
    }

    @GetMapping("/policy/list")
    @Operation(summary = "List policy packages")
    public ResponseEntity<?> policies() {
        return ResponseEntity.ok(promptPolicyService.listPolicies());
    }

    @GetMapping("/policy/detail")
    @Operation(summary = "Get policy package by policyId")
    public ResponseEntity<?> policyDetail(@RequestParam String policyId) {
        Object payload = promptPolicyService.getPolicyDetail(policyId);
        return ResponseEntity.ok(payload == null ? Map.of() : payload);
    }

    @PostMapping("/policy/save")
    @Operation(summary = "Save policy package")
    public ResponseEntity<?> savePolicy(@RequestBody PromptPolicySaveRequest request) {
        return ResponseEntity.ok(promptPolicyService.savePolicy(request));
    }

    @PostMapping("/policy/delete")
    @Operation(summary = "Delete policy package")
    public ResponseEntity<?> deletePolicy(@RequestBody Map<String, String> request) {
        String policyId = request == null ? "" : request.getOrDefault("policyId", "");
        promptPolicyService.deletePolicy(policyId);
        return ResponseEntity.ok(Map.of("success", true, "policyId", policyId));
    }

    @GetMapping("/policy/versions")
    @Operation(summary = "List policy package versions")
    public ResponseEntity<?> policyVersions(@RequestParam String policyId) {
        return ResponseEntity.ok(promptPolicyService.listPolicyVersions(policyId));
    }

    @PostMapping("/policy/activate")
    @Operation(summary = "Activate policy package version")
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
}
