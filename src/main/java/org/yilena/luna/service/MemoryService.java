package org.yilena.luna.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.yilena.luna.entity.Memory;

/**
 * 记忆服务接口，负责提供记忆实体的基础持久化能力。
 * 当前接口主要承接通用 CRUD 操作，供记忆管理链路复用。
 */
public interface MemoryService extends IService<Memory> {
}
