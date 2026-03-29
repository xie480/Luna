package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.Memory;

import java.util.List;

/**
 * Memory 數據訪問層
 * 繼承 BaseMapper 自動獲得 CRUD 能力
 */
@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {

    /**
     * 向量檢索長期記憶
     * @param vector 向量字符串
     * @param topK 返回條數
     */
    @Select("SELECT * FROM luna_memory WHERE embedding IS NOT NULL ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<Memory> searchByVector(@Param("vector") String vector, @Param("topK") int topK);

    /**
     * 向量檢索長期記憶（按 session_id 過濾）
     * @param vector 向量字符串
     * @param sessionId 會話標識
     * @param topK 返回條數
     */
    @Select("SELECT * FROM luna_memory WHERE embedding IS NOT NULL AND session_id = #{sessionId} ORDER BY embedding::vector <-> #{vector}::vector LIMIT #{topK}")
    List<Memory> searchByVectorAndSessionId(@Param("vector") String vector, @Param("sessionId") String sessionId, @Param("topK") int topK);
}
