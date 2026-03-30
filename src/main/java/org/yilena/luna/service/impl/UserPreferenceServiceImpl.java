package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.mapper.UserPreferenceMapper;
import org.yilena.luna.service.UserPreferenceService;

/**
 * 用戶偏好服務實現類
 */
@Service
public class UserPreferenceServiceImpl extends ServiceImpl<UserPreferenceMapper, UserPreference> implements UserPreferenceService {
} // 结束当前代码块
