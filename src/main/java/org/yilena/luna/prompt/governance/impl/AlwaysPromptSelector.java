package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;

/**
 * 常驻提示词判断器，负责识别需要在任何场景都强制装配的提示词。
 */
final class AlwaysPromptSelector {

    /**
     * 判断装配模式是否属于始终生效类型。
     */
    boolean isAlways(PromptAssemblyMode mode) {
        return mode == PromptAssemblyMode.ALWAYS;
    }
}
