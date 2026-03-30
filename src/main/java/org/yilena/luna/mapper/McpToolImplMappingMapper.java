package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpToolImplMapping;

@Mapper
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
