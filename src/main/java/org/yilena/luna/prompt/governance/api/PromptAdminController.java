package org.yilena.luna.prompt.governance.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.prompt.governance.PromptFrontendAdapter;
import org.yilena.luna.prompt.governance.PromptMutationService;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptPreviewService;
import org.yilena.luna.prompt.governance.PromptQueryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.dto.PromptPreviewRequest;
import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.dto.PromptVersionSwitchRequest;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

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

    @GetMapping("/categories")
    @Operation(summary = "List prompt categories")
    public ResponseEntity<?> categories() {
        return ResponseEntity.ok(promptQueryService.listCategories());
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
        Object payload = promptQueryService.detailByKey(key).<Object>map(item -> item).orElse(Map.of());
        return ResponseEntity.ok(payload);
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
        boolean exists = promptQueryService.detailByKey(key).isPresent();
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
        request.setPageNo(1L);
        request.setPageSize(500L);
        return request;
    }
}
