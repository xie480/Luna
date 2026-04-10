package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.mapper.UserPreferenceMapper;
import org.yilena.luna.service.UserPreferenceService;

/**
 * 用户偏好服务实现，负责封装用户画像和偏好设置的基础数据访问能力，供个性化上下文构建使用。
 */
@Service
public class UserPreferenceServiceImpl extends ServiceImpl<UserPreferenceMapper, UserPreference> implements UserPreferenceService {
}
