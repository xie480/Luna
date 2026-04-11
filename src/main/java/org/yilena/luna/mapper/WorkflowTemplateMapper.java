package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.WorkflowTemplate;

import java.util.List;

@Mapper
/**
 * 工作流模板 Mapper，负责维护工作流模板数据并提供向量检索能力。
 */
public interface WorkflowTemplateMapper extends BaseMapper<WorkflowTemplate> {

    @Select("SELECT * FROM workflow_template WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    /**
     * 按向量相似度搜索工作流模板。
     */
    List<WorkflowTemplate> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
