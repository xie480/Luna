package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanPhase;

@Mapper
/**
 * 计划阶段 Mapper，负责对计划阶段实体执行基础持久化操作，
 * 为阶段级调度与流程展示提供底层支持。
 */
public interface PlanPhaseMapper extends BaseMapper<PlanPhase> {
}
