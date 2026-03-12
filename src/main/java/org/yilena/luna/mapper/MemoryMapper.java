package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.Memory;

/**
 * Memory 數據訪問層
 * 繼承 BaseMapper 自動獲得 CRUD 能力
 */
@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {
}
