package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.PlanReport;

@Mapper
/**
 * 计划报告 Mapper，负责对计划报告记录执行基础持久化操作。
 */
public interface PlanReportMapper extends BaseMapper<PlanReport> {
}
