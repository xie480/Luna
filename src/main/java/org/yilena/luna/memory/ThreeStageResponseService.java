package org.yilena.luna.memory;

import org.yilena.luna.memory.model.StructuredContextPackage;

/**
 * 三阶段回复服务接口，负责分别生成综合摘要与最终回复文本，
 * 在多阶段提示链路中承接工具上下文和结构化记忆包。
 */
public interface ThreeStageResponseService {
    String generateSynthesisBrief(String userInput,
                                  String toolContext,
                                  StructuredContextPackage contextPackage);

    String generateFinalResponse(String userInput,
                                 String toolContext,
                                 StructuredContextPackage contextPackage);
}
