package org.yilena.luna.prompt.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;

@Mapper
/**
 * 提示词分类 Mapper，负责对提示词分类实体执行基础持久化操作，
 * 为分类树构建和分类查询提供底层访问能力。
 */
public interface PromptCategoryMapper extends BaseMapper<PromptCategoryEntity> {
}
