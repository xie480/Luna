package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.McpServerRegistry;

@Mapper
/**
 * MCP 服务注册表 Mapper，负责对 MCP 服务注册信息执行基础持久化操作。
 */
public interface McpServerRegistryMapper extends BaseMapper<McpServerRegistry> {
}
