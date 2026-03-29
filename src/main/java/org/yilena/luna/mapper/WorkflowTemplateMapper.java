package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.WorkflowTemplate;

import java.util.List;

@Mapper
public interface WorkflowTemplateMapper extends BaseMapper<WorkflowTemplate> {

    @Select("SELECT * FROM workflow_template WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<WorkflowTemplate> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
