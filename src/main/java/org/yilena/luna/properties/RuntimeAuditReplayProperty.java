package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "audit")
@Data
public class RuntimeAuditReplayProperty {

    private String replayMode = "window";
    private int toolResultsWindowLimit = 8;
    private int contextSnapshotsWindowLimit = 3;

    public boolean fullReplayMode() {
        return "full".equalsIgnoreCase(replayMode);
    }

    public int safeToolResultsWindowLimit() {
        return toolResultsWindowLimit <= 0 ? 8 : toolResultsWindowLimit;
    }

    public int safeContextSnapshotsWindowLimit() {
        return contextSnapshotsWindowLimit <= 0 ? 3 : contextSnapshotsWindowLimit;
    }
}
