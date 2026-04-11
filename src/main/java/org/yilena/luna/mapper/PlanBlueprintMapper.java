package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanBlueprint;

@Mapper
/**
 * 计划蓝图 Mapper，负责对计划蓝图实体执行基础持久化操作，
 * 为规划阶段和蓝图展示提供底层数据支持。
 */
public interface PlanBlueprintMapper extends BaseMapper<PlanBlueprint> {
}
