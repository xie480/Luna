package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanEdge;

@Mapper
/**
 * 计划边关系 Mapper，负责对计划节点依赖边实体执行基础持久化操作，
 * 为计划图构建和执行依赖解析提供底层支持。
 */
public interface PlanEdgeMapper extends BaseMapper<PlanEdge> {
}
