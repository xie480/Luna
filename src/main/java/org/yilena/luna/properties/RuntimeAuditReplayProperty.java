package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 运行审计回放配置类，负责控制回放模式以及工具结果和上下文快照的窗口大小。
 */
@Configuration
@ConfigurationProperties(prefix = "audit")
@Data
public class RuntimeAuditReplayProperty {

    /**
     * 审计回放模式，如 full 表示完整回放。
     */
    private String replayMode = "full";

    /**
     * 回放时保留的工具结果窗口上限。
     */
    private int toolResultsWindowLimit = 8;

    /**
     * 回放时保留的上下文快照窗口上限。
     */
    private int contextSnapshotsWindowLimit = 3;

    /**
     * 判断当前是否为完整回放模式。
     */
    public boolean fullReplayMode() {
        return "full".equalsIgnoreCase(replayMode);
    }

    /**
     * 获取安全的工具结果窗口大小，非法配置时回退默认值。
     */
    public int safeToolResultsWindowLimit() {
        return toolResultsWindowLimit <= 0 ? 8 : toolResultsWindowLimit;
    }

    /**
     * 获取安全的上下文快照窗口大小，非法配置时回退默认值。
     */
    public int safeContextSnapshotsWindowLimit() {
        return contextSnapshotsWindowLimit <= 0 ? 3 : contextSnapshotsWindowLimit;
    }
}
