package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanEventLog;

@Mapper
/**
 * 计划事件日志 Mapper，负责对计划事件日志实体执行基础持久化操作，
 * 为计划执行审计和事件回放提供底层支持。
 */
public interface PlanEventLogMapper extends BaseMapper<PlanEventLog> {
}
