package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.UserPreference;

import java.util.List;
import java.util.Map;

@Mapper
/**
 * 用户偏好 Mapper，负责对用户偏好实体执行基础持久化与向量检索操作，
 * 为偏好管理和偏好召回提供底层支持。
 */
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {

    @Select("""
            select pref_key, pref_value, description, updated_at
            from user_preference
            where coalesce(deleted, 0) = 0
            order by updated_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> selectResourcePreferences(@Param("limit") int limit);

    @Select("""
            select pref_key, pref_value, description, updated_at
            from user_preference
            where coalesce(deleted, 0) = 0
              and pref_key = #{prefKey}
            order by updated_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> selectResourcePreferencesByKey(@Param("prefKey") String prefKey,
                                                             @Param("limit") int limit);

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
