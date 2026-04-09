package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;

final class AlwaysPromptSelector {

    boolean isAlways(PromptAssemblyMode mode) {
        return mode == PromptAssemblyMode.ALWAYS;
    }
}
