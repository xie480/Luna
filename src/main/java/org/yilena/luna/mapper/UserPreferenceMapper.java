package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.UserPreference;

import java.util.List;

/**
 * 用戶偏好數據訪問層
 */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {

    /**
     * 向量檢索偏好
     * @param vector 向量字符串
     * @param topK 返回條數
     */
    @Select("""
            select fact_id as id,
                   fact_key as pref_key,
                   fact_value_text as pref_value,
                   description,
                   embedding,
                   created_at,
                   updated_at,
                   0 as deleted
            from relational_semantic_fact
            where deleted = false
              and embedding is not null
            order by embedding::vector <-> #{vector}::vector
            limit #{topK}
            """)
    List<UserPreference> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
