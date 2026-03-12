package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.UserPreference;

/**
 * 用戶偏好數據訪問層
 */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {
}
