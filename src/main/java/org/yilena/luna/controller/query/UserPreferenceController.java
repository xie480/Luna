package org.yilena.luna.controller.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.entity.query.UserPreferencePageQueryRequest;

@RestController
@RequestMapping("/luna/api/query/user-preference")
@RequiredArgsConstructor
@Tag(name = "历史偏好查询接口", description = "保留旧版用户偏好查询路径并提示已迁移")
/**
 * 历史用户偏好查询控制器，保留旧版路径用于兼容迁移提示。
 */
public class UserPreferenceController extends BasePageQueryController {

    @PostMapping({"", "/page"})
    /**
     * 统一返回接口退役提示，引导调用方切换到新的关系语义事实表。
     */
    @Operation(summary = "查询历史用户偏好接口", description = "该接口已退役，调用后会返回 410 并提示使用新的关系语义事实表")
    public ResponseEntity<Object> page(@RequestBody(required = false) UserPreferencePageQueryRequest req) {
        return ResponseEntity.status(410).body(error("legacy user_preference API retired, use relational_semantic_fact"));
    }
}
