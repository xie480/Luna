package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.yilena.luna.entity.PlanEventLog; // import dependency

@Mapper // declare annotation
public interface PlanEventLogMapper extends BaseMapper<PlanEventLog> { // define interface
} // block end
