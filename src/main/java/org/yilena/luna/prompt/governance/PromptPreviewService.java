package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.Map;

/**
 * 提示词预览服务接口，负责在不真正执行主链路的情况下预览提示词匹配与组装结果，
 * 便于治理后台调试策略配置。
 */
public interface PromptPreviewService {
    Map<String, Object> previewMatch(PromptResolveContext context);

    Map<String, Object> previewAssemble(PromptResolveContext context);
}
