package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.LunaLog;

@Mapper
/**
 * 系统日志 Mapper，负责对 Luna 日志实体执行基础持久化操作。
 */
public interface LunaLogMapper extends BaseMapper<LunaLog> {
}
