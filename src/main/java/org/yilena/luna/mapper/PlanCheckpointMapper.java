package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanCheckpoint;

@Mapper
/**
 * 计划检查点 Mapper，负责对计划检查点实体执行基础持久化操作，
 * 为长任务恢复和阶段回滚提供底层数据支持。
 */
public interface PlanCheckpointMapper extends BaseMapper<PlanCheckpoint> {
}
