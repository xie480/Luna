package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.AgentMemory;

/**
 * 长期记忆数据访问层
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {
}
