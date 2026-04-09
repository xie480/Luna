package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptMatchOutcome;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.Set;

public interface PromptMatcher {
    PromptMatchOutcome match(PromptItemRecord item,
                             PromptResolveContext context,
                             Set<String> policyIncludes,
                             Set<String> policyExcludes);
}
