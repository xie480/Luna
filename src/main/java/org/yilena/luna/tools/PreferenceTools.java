package org.yilena.luna.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
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

    @LunaState(value = "Luna 正在记录主人的偏好...", status = "PREFERENCE")
    @Tool("""
    【用户偏好(UserPreference) CRUD 工具】
    目标实体类定义 (Schema):
    - id: Long (自动生成, 插入时不填)
    - prefKey: String (必填, 偏好键, 如 "theme", "nickname")
    - prefValue: String (必填, 偏好值)
    - description: String (选填, 描述/备注)
    - createdAt: DateTime (自动生成)
    - updatedAt: DateTime (自动生成)
    - deleted: Integer (自动生成, 逻辑删除标记)

    业务流程约束: 当偏好变更时，必须先调用 QUERY 查出旧偏好，再决定是 UPDATE 还是 DELETE 后重新 INSERT。

    参数说明:
    - action: 必填。可选值: "INSERT", "UPDATE", "DELETE", "QUERY"
    - id: UPDATE 和 DELETE 时必填。
    - mode: UPDATE 时必填。可选值: "PATCH" (部分更新，忽略空值), "PUT" (全量替换，空值将覆盖原值)
    - hardDelete: DELETE 时选填。true 为物理删除，false 为逻辑删除(默认)。
    - prefKey, prefValue, description: 根据 action 和 mode 提供。

    返回格式示例:
    - 成功: {"status":"success", "data": {"id":1, "prefKey":"theme", "prefValue":"dark", ...}}
    - 失败: {"status":"error", "message":"INSERT 操作必须提供 prefKey 和 prefValue"}
    """)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_PREFERENCE, type = LogType.TOOL_CALL)
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
