package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.yilena.luna.entity.PlanEdge; // import dependency

@Mapper // declare annotation
public interface PlanEdgeMapper extends BaseMapper<PlanEdge> { // define interface
} // block end
