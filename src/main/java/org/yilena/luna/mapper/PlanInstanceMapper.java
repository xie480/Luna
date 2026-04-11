package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanInstance;

@Mapper
/**
 * 计划实例 Mapper，负责对计划实例实体执行基础持久化操作，
 * 为计划编排与执行阶段维护计划级运行态。
 */
public interface PlanInstanceMapper extends BaseMapper<PlanInstance> {
}
