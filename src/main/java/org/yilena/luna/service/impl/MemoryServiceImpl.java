package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.service.MemoryService;

/**
 * 长期记忆服务实现，负责封装记忆实体的基础数据库访问能力，供记忆写入、检索和管理流程复用。
 */
@Service
public class MemoryServiceImpl extends ServiceImpl<MemoryMapper, Memory> implements MemoryService {
}
