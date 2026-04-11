package org.yilena.luna.prompt.governance.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
/**
 * 提示词检索请求模型，负责承载治理后台查询提示词时的分类、关键字、能力开关和分页条件，
 * 用于统一提示词检索入口参数。
 */
public class PromptSearchRequest {
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
     * 按提示词键模糊检索条件。
     */
    private String keyLike;
    /**
     * 按提示词名称模糊检索条件。
     */
    private String nameLike;
    /**
     * 按提示词内容模糊检索条件。
     */
    private String valueLike;
    /**
     * 是否只查询包含模板变量的提示词。
     */
    private Boolean hasTemplateVariables;
    /**
     * 是否只查询开启关键字匹配的提示词。
     */
    private Boolean keywordMatchEnabled;
    /**
     * 组装模式过滤条件。
     */
    private String assemblyMode;
    /**
     * 是否只查询启用中的提示词。
     */
    private Boolean enabled;
    /**
     * 是否包含已禁用提示词的兼容字段。
     */
    @JsonAlias({"include_disabled"})
    private Boolean includeDisabled;
    /**
     * 分页页码，从 1 开始。
     */
    private Long pageNo = 1L;
    /**
     * 分页大小。
     */
    private Long pageSize = 20L;
}
