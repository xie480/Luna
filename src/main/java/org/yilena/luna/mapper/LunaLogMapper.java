package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.yilena.luna.entity.LunaLog; // import dependency

@Mapper // declare annotation
public interface LunaLogMapper extends BaseMapper<LunaLog> { // define interface
} // block end
