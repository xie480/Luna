package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;

public interface PromptResolverService {
    PromptResolveResult resolve(PromptResolveContext context);
}

