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
@Tag(name = "legacy-user-preference")
public class UserPreferenceController extends BasePageQueryController {

    @PostMapping({"", "/page"})
    @Operation(summary = "legacy user_preference query (retired)")
    public ResponseEntity<Object> page(@RequestBody(required = false) UserPreferencePageQueryRequest req) {
        return ResponseEntity.status(410).body(error("legacy user_preference API retired, use relational_semantic_fact"));
    }
}
