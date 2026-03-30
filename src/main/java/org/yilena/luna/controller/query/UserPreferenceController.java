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

    private final UserPreferenceService userPreferenceService; // 声明成员字段

    @PostMapping({"", "/page"}) // 声明注解
    @Operation(summary = "分页查询用户偏好") // 声明注解
    public ResponseEntity<Object> page(@RequestBody(required = false) UserPreferencePageQueryRequest req) { // 定义方法签名
        try { // 尝试执行核心逻辑
            UserPreferencePageQueryRequest request = req == null ? new UserPreferencePageQueryRequest() : req; // 执行赋值操作
            Page<UserPreference> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize())); // 执行赋值操作

            LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>(); // 执行赋值操作
            if (hasText(request.getPrefKey())) { // 进行条件判断
                wrapper.like(UserPreference::getPrefKey, request.getPrefKey().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getPrefValue())) { // 进行条件判断
                wrapper.like(UserPreference::getPrefValue, request.getPrefValue().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getDescription())) { // 进行条件判断
                wrapper.like(UserPreference::getDescription, request.getDescription().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getStartTime())) { // 进行条件判断
                wrapper.ge(UserPreference::getCreatedAt, parseDateTime(request.getStartTime())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getEndTime())) { // 进行条件判断
                wrapper.le(UserPreference::getCreatedAt, parseDateTime(request.getEndTime())); // 执行语句逻辑
            } // 结束当前代码块
            wrapper.orderByDesc(UserPreference::getCreatedAt); // 执行语句逻辑

            IPage<UserPreference> result = userPreferenceService.page(page, wrapper); // 执行赋值操作
            return ResponseEntity.ok(PagedResponse.from(result)); // 返回处理结果
        } catch (IllegalArgumentException e) { // 开始新的代码块
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return ResponseEntity.internalServerError().body(error("查询用户偏好失败: " + e.getMessage())); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
