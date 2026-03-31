package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.McpTool;

import java.util.List;

@Mapper
@Deprecated
public interface McpToolMapper extends BaseMapper<McpTool> {

    /**
     * 向量檢索 (依賴 PGVector 插件)
     * 使用 <-> 運算符計算歐式距離 (或餘弦相似度，取決於索引)
     * 
     * @param vector 向量字符串 (格式: "[0.1, 0.2, ...]")
     * @param topK 返回數量
     * @return 匹配的記錄
     */
    @Select("SELECT * FROM mcp_tools WHERE 1 = 0")
    List<McpTool> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
