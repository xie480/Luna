package org.yilena.luna.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yilena.luna.grpc.EmbeddingRequest;
import org.yilena.luna.grpc.EmbeddingResponse;
import org.yilena.luna.grpc.LunaInferenceServiceGrpc;
import org.yilena.luna.grpc.RerankRequest;
import org.yilena.luna.grpc.RerankResponse;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * gRPC 常驻推理客户端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceGrpcClient {

    private final ManagedChannel managedChannel;

    @Value("${inference.grpc.timeout-ms:1500}")
    private long timeoutMs;

    public String embedding(String text) {
        try {
            LunaInferenceServiceGrpc.LunaInferenceServiceBlockingStub stub =
                    LunaInferenceServiceGrpc.newBlockingStub(managedChannel)
                            .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

            EmbeddingResponse resp = stub.embedding(
                    EmbeddingRequest.newBuilder().setText(text).build()
            );
            if (!resp.getSuccess()) {
                throw new RuntimeException(resp.getErrorMessage());
            }
            return resp.getVectorJson();
        } catch (StatusRuntimeException e) {
            log.error("gRPC embedding 调用失败: {}", e.getMessage());
            throw e;
        }
    }

    public List<Double> rerank(String query, List<String> documents) {
        try {
            LunaInferenceServiceGrpc.LunaInferenceServiceBlockingStub stub =
                    LunaInferenceServiceGrpc.newBlockingStub(managedChannel)
                            .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

            RerankRequest req = RerankRequest.newBuilder()
                    .setQuery(query)
                    .addAllDocuments(documents)
                    .build();

            RerankResponse resp = stub.rerank(req);
            if (!resp.getSuccess()) {
                throw new RuntimeException(resp.getErrorMessage());
            }
            return resp.getScoresList();
        } catch (StatusRuntimeException e) {
            log.error("gRPC rerank 调用失败: {}", e.getMessage());
            throw e;
        }
    }
}
