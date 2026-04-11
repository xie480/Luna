package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;

/**
 * 提示词解析服务接口，负责根据运行上下文计算最终命中的提示词集合与槽位映射，
 * 为后续提示组装提供解析结果。
 */
public interface PromptResolverService {
    PromptResolveResult resolve(PromptResolveContext context);
}
