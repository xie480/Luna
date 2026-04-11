package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 已解析提示词模型，负责描述某条命中提示词的版本、分类、匹配原因和运行时槽位信息，
 * 供后续组装和快照阶段使用。
 */
public class ResolvedPromptItem {
    /**
     * 提示词主记录标识。
     */
    Long itemId;
    /**
     * 提示词版本标识。
     */
    Long versionId;
    /**
     * 提示词唯一键。
     */
    String key;
    /**
     * 提示词名称。
     */
    String name;
    /**
     * 提示词正文内容。
     */
    String value;
    /**
     * 一级分类。
     */
    String category;
    /**
     * 二级分类。
     */
    String subCategory;
    /**
     * 提示词描述。
     */
    String description;
    /**
     * 运行时槽位名称。
     */
    String runtimeSlot;
    /**
     * 组装模式。
     */
    String assemblyMode;
    /**
     * 命中原因说明。
     */
    String matchReason;
    /**
     * 是否受策略干预命中。
     */
    boolean policyApplied;
    /**
     * 是否包含模板变量。
     */
    boolean hasTemplateVariables;
    /**
     * 是否启用关键字匹配。
     */
    boolean keywordMatchEnabled;
    /**
     * 优先级，值越大越优先。
     */
    Integer priority;
    /**
     * 版本号。
     */
    String version;
    /**
     * 版本标签。
     */
    String versionLabel;
    /**
     * 组装器版本号。
     */
    String assemblerVersion;
}
