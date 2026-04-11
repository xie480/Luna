package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
/**
 * 提示词记录模型，负责承载运行时或治理后台读取到的提示词完整信息，
 * 包括版本、匹配规则、编辑策略和启用状态等核心数据。
 */
public class PromptItemRecord {
    /**
     * 提示词主记录标识。
     */
    Long itemId;
    /**
     * 当前生效版本标识。
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
     * 是否包含模板变量。
     */
    boolean hasTemplateVariables;
    /**
     * 模板变量列表。
     */
    List<String> templateVariables;
    /**
     * 是否启用关键字匹配。
     */
    boolean keywordMatchEnabled;
    /**
     * 关键字匹配列表。
     */
    List<String> matchKeywords;
    /**
     * 组装模式。
     */
    String assemblyMode;
    /**
     * 匹配范围约束。
     */
    MatchScope matchScope;
    /**
     * 编辑策略配置。
     */
    EditPolicy editPolicy;
    /**
     * 提示词是否启用。
     */
    boolean enabled;
    /**
     * 优先级，值越大越优先。
     */
    Integer priority;
    /**
     * 提示词状态。
     */
    String status;
    /**
     * 版本号。
     */
    String version;
    /**
     * 版本标签。
     */
    String versionLabel;
    /**
     * 本次变更说明。
     */
    String changeNote;
}
