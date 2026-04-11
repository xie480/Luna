package org.yilena.luna.prompt.governance.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
/**
 * 提示词新增或更新请求模型，负责承载提示词基础信息、匹配规则、编辑策略和版本说明，
 * 作为治理后台创建与更新提示词的统一入参。
 */
public class PromptUpsertRequest {
    /**
     * 提示词唯一键。
     */
    private String key;
    /**
     * 提示词名称。
     */
    private String promptName;
    /**
     * 提示词正文内容。
     */
    private String value;
    /**
     * 提示词一级分类。
     */
    @JsonAlias({"category_key"})
    private String category;
    /**
     * 一级分类键的兼容字段。
     */
    private String categoryKey;
    /**
     * 提示词二级分类。
     */
    private String subCategory;
    /**
     * 提示词描述说明。
     */
    private String description;
    /**
     * 运行时槽位名称。
     */
    private String runtimeSlot;
    /**
     * 是否包含模板变量。
     */
    private Boolean hasTemplateVariables;
    /**
     * 模板变量列表。
     */
    private List<String> templateVariables;
    /**
     * 是否启用关键字匹配。
     */
    private Boolean keywordMatchEnabled;
    /**
     * 关键字匹配列表。
     */
    private List<String> matchKeywords;
    /**
     * 提示词组装模式。
     */
    private String assemblyMode;
    /**
     * 匹配范围配置。
     */
    private Map<String, Object> matchScope;
    /**
     * 编辑策略配置。
     */
    private Map<String, Object> editPolicy;
    /**
     * 提示词是否启用。
     */
    private Boolean enabled;
    /**
     * 提示词优先级，值越大越优先。
     */
    private Integer priority;
    /**
     * 提示词状态标记。
     */
    private String status;
    /**
     * 版本号。
     */
    private String version;
    /**
     * 版本展示标签。
     */
    private String versionLabel;
    /**
     * 本次变更说明。
     */
    private String changeNote;
    /**
     * 是否仅执行预览而不真正落库。
     */
    private Boolean previewOnly;
}
