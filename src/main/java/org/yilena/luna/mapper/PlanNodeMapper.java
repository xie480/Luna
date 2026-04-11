package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanNode;

@Mapper
/**
 * 计划节点 Mapper，负责对计划节点实体执行基础持久化操作，
 * 为节点调度、执行和状态追踪提供底层支持。
 */
public interface PlanNodeMapper extends BaseMapper<PlanNode> {
}
