package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.ScheduleTask;
import org.yilena.luna.mapper.ScheduleTaskMapper;
import org.yilena.luna.service.ScheduleTaskService;

/**
 * 日程任务服务实现，负责封装提醒与待办任务的基础持久化操作，供调度与任务管理链路调用。
 */
@Service
public class ScheduleTaskServiceImpl extends ServiceImpl<ScheduleTaskMapper, ScheduleTask> implements ScheduleTaskService {
}
