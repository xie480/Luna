package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RecoveryState {
    String interruptedAt;
    String interruptReason;
    String recoveryEvent;
    String recoverySnapshotId;
}

