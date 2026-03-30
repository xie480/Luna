package org.yilena.luna.controller.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.entity.query.PagedResponse;
import org.yilena.luna.entity.query.UserPreferencePageQueryRequest;
import org.yilena.luna.service.UserPreferenceService;

@RestController
@RequestMapping("/luna/api/query/user-preference")
@RequiredArgsConstructor
@Tag(name = "用户偏好接口")
/**
 * UserPreferenceController ??
 */
public class UserPreferenceController extends BasePageQueryController {

    private final UserPreferenceService userPreferenceService;

    @PostMapping({"", "/page"})
    @Operation(summary = "分页查询用户偏好")
    public ResponseEntity<Object> page(@RequestBody(required = false) UserPreferencePageQueryRequest req) {
        try {
            // 请求体为空时使用默认查询对象，保证分页参数可用。
            UserPreferencePageQueryRequest request = req == null ? new UserPreferencePageQueryRequest() : req;
            Page<UserPreference> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

            // 按入参动态拼接查询条件，仅对非空字段生效。
            LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
            if (hasText(request.getPrefKey())) {
                wrapper.like(UserPreference::getPrefKey, request.getPrefKey().trim());
            }
            if (hasText(request.getPrefValue())) {
                wrapper.like(UserPreference::getPrefValue, request.getPrefValue().trim());
            }
            if (hasText(request.getDescription())) {
                wrapper.like(UserPreference::getDescription, request.getDescription().trim());
            }
            if (hasText(request.getStartTime())) {
                wrapper.ge(UserPreference::getCreatedAt, parseDateTime(request.getStartTime()));
            }
            if (hasText(request.getEndTime())) {
                wrapper.le(UserPreference::getCreatedAt, parseDateTime(request.getEndTime()));
            }
            wrapper.orderByDesc(UserPreference::getCreatedAt);

            // 执行分页查询并统一封装分页返回结构。
            IPage<UserPreference> result = userPreferenceService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询用户偏好失败: " + e.getMessage()));
        }
    }
}
