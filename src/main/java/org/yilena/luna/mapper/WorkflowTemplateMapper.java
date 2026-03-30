package org.yilena.luna.mapper; // define package

import com.baomidou.mybatisplus.core.mapper.BaseMapper; // import dependency
import org.apache.ibatis.annotations.Mapper; // import dependency
import org.apache.ibatis.annotations.Param; // import dependency
import org.apache.ibatis.annotations.Select; // import dependency
import org.yilena.luna.entity.WorkflowTemplate; // import dependency

import java.util.List; // import dependency

@Mapper // declare annotation
public interface WorkflowTemplateMapper extends BaseMapper<WorkflowTemplate> { // define interface

    @Select("SELECT * FROM workflow_template WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}") // declare annotation
    List<WorkflowTemplate> searchByVector(@Param("vector") String vector, @Param("topK") int topK); // business logic
} // block end
