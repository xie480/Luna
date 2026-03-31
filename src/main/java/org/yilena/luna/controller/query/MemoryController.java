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
@Tag(name = "legacy-memory")
public class MemoryController extends BasePageQueryController {

    @PostMapping({"", "/page"})
    @Operation(summary = "legacy luna_memory query (retired)")
    public ResponseEntity<Object> page(@RequestBody(required = false) MemoryPageQueryRequest req) {
        return ResponseEntity.status(410).body(error("legacy luna_memory API retired, use v2 runtime memory tables"));
    }
}
