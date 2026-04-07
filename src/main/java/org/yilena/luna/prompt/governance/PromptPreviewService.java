package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.Map;

public interface PromptPreviewService {
    Map<String, Object> previewMatch(PromptResolveContext context);

    Map<String, Object> previewAssemble(PromptResolveContext context);
}

