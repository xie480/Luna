package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpToolCatalog;

import java.util.List;

@Mapper
/**
 * MCP 工具目录 Mapper，负责对 MCP 工具目录实体执行基础持久化和向量检索操作，
 * 为工具能力同步、检索和目录维护提供底层支持。
 */
public interface McpToolCatalogMapper extends BaseMapper<McpToolCatalog> {

    @Select("SELECT * FROM mcp_tool_catalog WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<McpToolCatalog> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
