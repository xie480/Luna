package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.yilena.luna.entity.PlanNode; // import dependency

@Mapper // declare annotation
public interface PlanNodeMapper extends BaseMapper<PlanNode> { // define interface
} // block end
