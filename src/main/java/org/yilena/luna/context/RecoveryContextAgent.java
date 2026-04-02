package org.yilena.luna.context;

import org.yilena.luna.memory.model.StructuredContextPackage;

public interface RecoveryContextAgent {
    StructuredContextPackage recover(String sessionId,
                                     StructuredContextPackage contextPackage,
                                     String recoveryEvent,
                                     String interruptReason);
}

