package org.yilena.luna.prompt.governance.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptFrontendAdapter;
import org.yilena.luna.prompt.governance.PromptMutationService;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptPreviewService;
import org.yilena.luna.prompt.governance.PromptQueryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.model.EditPolicy;
import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;

class PromptAdminControllerSaveTest {

    @Test
    void saveShouldUseExistsByKeyAndRouteToUpdate() {
        PromptQueryService queryService = Mockito.mock(PromptQueryService.class);
        PromptMutationService mutationService = Mockito.mock(PromptMutationService.class);
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptAdminController controller = new PromptAdminController(
                queryService,
                mutationService,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptPreviewService.class),
                Mockito.mock(PromptFrontendAdapter.class),
                Mockito.mock(PromptPolicyService.class),
                registryService,
                Mockito.mock(PromptCategoryService.class)
        );
        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("agent.tool.decision");
        PromptItemRecord updated = PromptItemRecord.builder()
                .key("agent.tool.decision")
                .name("agent.tool.decision")
                .value("v2")
                .category("tooling")
                .subCategory("default")
                .runtimeSlot("agent.tool_decision")
                .hasTemplateVariables(true)
                .templateVariables(List.of("runtimePromptInput"))
                .keywordMatchEnabled(false)
                .matchKeywords(List.of())
                .assemblyMode("AGENT_ONLY")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.executionDefault())
                .enabled(true)
                .priority(100)
                .status("enabled")
                .version("1.0.1")
                .versionLabel("1.0.1")
                .changeNote("")
                .build();
        Mockito.when(registryService.existsByKey("agent.tool.decision")).thenReturn(true);
        Mockito.when(mutationService.update(request)).thenReturn(updated);

        ResponseEntity<?> response = controller.save(request);

        Assertions.assertEquals(updated, response.getBody());
        Mockito.verify(registryService).existsByKey("agent.tool.decision");
        Mockito.verify(mutationService).update(request);
        Mockito.verify(mutationService, Mockito.never()).create(request);
        Mockito.verifyNoInteractions(queryService);
    }
}
