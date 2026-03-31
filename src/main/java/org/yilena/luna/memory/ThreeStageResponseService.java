package org.yilena.luna.memory;

import org.yilena.luna.memory.model.StructuredContextPackage;

public interface ThreeStageResponseService {
    String generateSynthesisBrief(String userInput,
                                  String toolContext,
                                  StructuredContextPackage contextPackage);

    String generateFinalResponse(String userInput,
                                 String toolContext,
                                 StructuredContextPackage contextPackage);
}
