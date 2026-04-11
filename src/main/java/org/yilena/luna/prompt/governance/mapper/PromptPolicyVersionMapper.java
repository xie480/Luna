package org.yilena.luna.prompt.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.prompt.governance.entity.PromptPolicyVersionEntity;

@Mapper
/**
 * 提示策略版本 Mapper，负责对提示策略版本实体执行基础持久化操作，
 * 支撑策略版本切换和版本历史管理。
 */
public interface PromptPolicyVersionMapper extends BaseMapper<PromptPolicyVersionEntity> {
}
