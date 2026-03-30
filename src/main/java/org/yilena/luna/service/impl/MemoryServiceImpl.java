package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.service.MemoryService;

/**
 * Memory 服務實現類
 */
@Service
public class MemoryServiceImpl extends ServiceImpl<MemoryMapper, Memory> implements MemoryService {
}
