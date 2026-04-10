package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.LunaLog;
import org.yilena.luna.mapper.LunaLogMapper;
import org.yilena.luna.service.LunaLogService;

/**
 * 系统日志服务实现，负责提供 Luna 运行日志的基础持久化能力，供审计、排障和日志查询使用。
 */
@Service
public class LunaLogServiceImpl extends ServiceImpl<LunaLogMapper, LunaLog> implements LunaLogService {
}
