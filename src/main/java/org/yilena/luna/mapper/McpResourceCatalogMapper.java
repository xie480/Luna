package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpResourceCatalog;

import java.util.List;

@Mapper
public interface McpResourceCatalogMapper extends BaseMapper<McpResourceCatalog> {

    @Select("SELECT * FROM mcp_resource_catalog WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<McpResourceCatalog> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
