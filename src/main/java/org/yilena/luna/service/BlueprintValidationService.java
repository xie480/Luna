package org.yilena.luna.service;

import java.util.Map;

/**
 * 蓝图校验服务
 */
public interface BlueprintValidationService {

    /**
     * 校验蓝图
     *
     * @param blueprint 蓝图对象
     * @return null 表示通过；非 null 表示错误信息
     */
    String validate(Map<String, Object> blueprint);
}
