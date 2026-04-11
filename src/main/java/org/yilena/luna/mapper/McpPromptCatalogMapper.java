package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpPromptCatalog;

import java.util.List;

@Mapper
/**
 * MCP 提示词目录 Mapper，负责对 MCP 提示词目录实体执行基础持久化和向量检索操作，
 * 为提示词能力同步与检索提供底层支持。
 */
public interface McpPromptCatalogMapper extends BaseMapper<McpPromptCatalog> {

    @Select("SELECT * FROM mcp_prompt_catalog WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<McpPromptCatalog> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
