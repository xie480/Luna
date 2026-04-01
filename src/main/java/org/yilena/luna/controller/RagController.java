package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.rag.api.RetrievalService;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/luna/api/rag")
@Tag(name = "RAG 检索接口")
public class RagController {

    private final RetrievalService retrievalService;

    @Operation(description = "执行一次完整的 RAG 检索流程")
    @PostMapping("/retrieve")
    public ResponseEntity<RetrievalResponse> retrieve(@RequestBody RetrievalRequest request) {
        return ResponseEntity.ok(retrievalService.retrieve(request));
    }
}
