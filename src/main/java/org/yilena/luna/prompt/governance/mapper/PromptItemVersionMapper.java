package org.yilena.luna.prompt.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;

@Mapper
/**
 * 提示词版本 Mapper，负责对提示词版本实体执行基础持久化操作，
 * 为版本查询、切换和回滚提供底层数据访问能力。
 */
public interface PromptItemVersionMapper extends BaseMapper<PromptItemVersionEntity> {
}
