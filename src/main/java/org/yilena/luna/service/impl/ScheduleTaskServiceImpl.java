package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.ScheduleTask;
import org.yilena.luna.mapper.ScheduleTaskMapper;
import org.yilena.luna.service.ScheduleTaskService;

/**
 * 日程任務服務實現類
 */
@Service
public class ScheduleTaskServiceImpl extends ServiceImpl<ScheduleTaskMapper, ScheduleTask> implements ScheduleTaskService {
}
