package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpToolImplMapping;

@Mapper
/**
 * MCP 工具实现映射 Mapper，负责维护工具目录项与本地实现之间的映射关系，
 * 供运行时根据服务端编码和工具名定位实际执行实现。
 */
public interface McpToolImplMappingMapper extends BaseMapper<McpToolImplMapping> {

    @Select("""
            SELECT *
            FROM mcp_tool_impl_mapping
            WHERE server_code = #{serverCode}
              AND tool_name = #{toolName}
              AND enabled = true
            LIMIT 1
            """)
    McpToolImplMapping findEnabledMapping(@Param("serverCode") String serverCode,
                                          @Param("toolName") String toolName);
}
