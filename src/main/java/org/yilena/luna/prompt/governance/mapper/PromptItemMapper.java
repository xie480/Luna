package org.yilena.luna.prompt.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;

@Mapper
/**
 * 提示词条目 Mapper，负责对提示词主表实体执行基础持久化操作，
 * 支撑提示词注册与查询链路读取主记录。
 */
public interface PromptItemMapper extends BaseMapper<PromptItemEntity> {
}
