package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.apache.ibatis.annotations.Param; // import dependency
import org.apache.ibatis.annotations.Select; // import dependency
import org.yilena.luna.entity.McpToolImplMapping; // import dependency

@Mapper // declare annotation
public interface McpToolImplMappingMapper extends BaseMapper<McpToolImplMapping> { // define interface

    @Select(""" // declare annotation
            SELECT * // business logic
            FROM mcp_tool_impl_mapping // business logic
            WHERE server_code = #{serverCode} // assignment or init
              AND tool_name = #{toolName} // assignment or init
              AND enabled = true // assignment or init
            LIMIT 1 // business logic
            """) // business logic
    McpToolImplMapping findEnabledMapping(@Param("serverCode") String serverCode, // business logic
                                          @Param("toolName") String toolName); // declare annotation
} // block end
