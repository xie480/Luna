package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.ScheduleTask;

/**
 * 日程任務數據訪問層
 */
@Mapper
public interface ScheduleTaskMapper extends BaseMapper<ScheduleTask> {
}
