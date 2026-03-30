package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.yilena.luna.entity.McpServerRegistry; // import dependency

@Mapper // declare annotation
public interface McpServerRegistryMapper extends BaseMapper<McpServerRegistry> { // define interface
} // block end
