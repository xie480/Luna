package org.yilena.luna.prompt.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;

@Mapper
/**
 * 提示策略 Mapper，负责对提示策略主表实体执行基础持久化操作，
 * 支撑策略包的查询与维护。
 */
public interface PromptPolicyMapper extends BaseMapper<PromptPolicyEntity> {
}
