package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpSkill;

import java.util.List;

@Mapper
public interface McpSkillMapper extends BaseMapper<McpSkill> {

    /**
     * 向量檢索 (依賴 PGVector 插件)
     * 
     * @param vector 向量字符串 (格式: "[0.1, 0.2, ...]")
     * @param topK 返回數量
     * @return 匹配的記錄
     */
    @Select("SELECT * FROM mcp_skills WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<McpSkill> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
