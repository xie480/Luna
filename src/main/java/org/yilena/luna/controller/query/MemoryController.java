package org.yilena.luna.controller.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.entity.query.MemoryPageQueryRequest;

@RestController
@RequestMapping("/luna/api/query/memory")
@RequiredArgsConstructor
@Tag(name = "历史内存查询接口", description = "保留旧版内存查询路径并明确提示接口已下线")
/**
 * 历史内存查询控制器，保留旧版查询入口用于兼容提示。
 */
public class MemoryController extends BasePageQueryController {

    @PostMapping({"", "/page"})
    /**
     * 明确告知调用方旧版内存查询接口已退役，并指向新的运行态内存表。
     */
    @Operation(summary = "查询历史内存接口", description = "该接口已退役，调用后会返回 410 并提示使用新的运行态内存表")
    public ResponseEntity<Object> page(@RequestBody(required = false) MemoryPageQueryRequest req) {
        return ResponseEntity.status(410).body(error("legacy luna_memory API retired, use v2 runtime memory tables"));
    }
}
