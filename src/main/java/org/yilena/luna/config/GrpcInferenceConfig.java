package org.yilena.luna.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC 推理通道配置
 */
@Slf4j
@Configuration
public class GrpcInferenceConfig {

    @Bean
    public ManagedChannel inferenceManagedChannel(
            @Value("${inference.grpc.host:127.0.0.1}") String host,
            @Value("${inference.grpc.port:50051}") int port
    ) {
        log.info("初始化 gRPC 推理通道: {}:{}", host, port);
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
    }
}
