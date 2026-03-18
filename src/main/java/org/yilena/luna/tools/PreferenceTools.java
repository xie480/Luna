package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.mapper.UserPreferenceMapper;

@Component
public class PreferenceTools extends BaseTool {

    private final UserPreferenceMapper userPreferenceMapper;

    public PreferenceTools(ObjectMapper objectMapper, UserPreferenceMapper userPreferenceMapper) {
        super(objectMapper);
        this.userPreferenceMapper = userPreferenceMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_PREFERENCE, status = LunaStateConstant.STATUS_PREFERENCE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_PREFERENCE, type = LogType.TOOL_CALL, content = "管理用户偏好设置")
    public String manageUserPreference(String action, Long id, String mode, String prefKey, String prefValue, String description, Boolean hardDelete) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (prefKey == null || prefValue == null) return error("INSERT 必须提供 prefKey 和 prefValue");
                UserPreference pref = UserPreference.builder().prefKey(prefKey).prefValue(prefValue).description(description).build();
                userPreferenceMapper.insert(pref);
                return success(userPreferenceMapper.selectById(pref.getId()));
            } else if ("QUERY".equalsIgnoreCase(action)) {
                LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
                if (prefKey != null) wrapper.eq(UserPreference::getPrefKey, prefKey);
                return success(userPreferenceMapper.selectList(wrapper));
            } else if ("UPDATE".equalsIgnoreCase(action)) {
                if (id == null || mode == null) return error("UPDATE 必须提供 id 和 mode(PATCH/PUT)");
                UserPreference existing = userPreferenceMapper.selectById(id);
                if (existing == null) return error("未找到 id=" + id + " 的记录");

                if ("PUT".equalsIgnoreCase(mode)) {
                    existing.setPrefKey(prefKey);
                    existing.setPrefValue(prefValue);
                    existing.setDescription(description);
                } else if ("PATCH".equalsIgnoreCase(mode)) {
                    if (prefKey != null) existing.setPrefKey(prefKey);
                    if (prefValue != null) existing.setPrefValue(prefValue);
                    if (description != null) existing.setDescription(description);
                } else {
                    return error("未知的 mode: " + mode);
                }
                userPreferenceMapper.updateById(existing);
                return success(userPreferenceMapper.selectById(id));
            } else if ("DELETE".equalsIgnoreCase(action)) {
                if (id == null) return error("DELETE 必须提供 id");
                if (Boolean.TRUE.equals(hardDelete)) {
                    SqlRunner.db().delete("DELETE FROM user_preference WHERE id = {0}", id);
                    return success("已执行物理删除 id=" + id);
                } else {
                    userPreferenceMapper.deleteById(id);
                    return success("已执行逻辑删除 id=" + id);
                }
            }
            return error("未知的 action: " + action);
        } catch (Exception e) {
            return error("操作异常: " + e.getMessage());
        }
    }
}
