package org.yilena.luna.config;

import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 该配置类负责为嵌入式 Tomcat 配置虚拟线程执行器，提升高并发请求下的线程利用效率。
 */
@Configuration
public class TomcatConfig {

    /**
     * 为 Tomcat 协议处理器注入虚拟线程执行器，让每个请求按任务创建轻量线程处理。
     */
    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
        return protocolHandler -> {
            Thread.Builder.OfVirtual ofVirtual = Thread.ofVirtual().name("VirtualThread#", 1);
            ThreadFactory factory = ofVirtual.factory();
            protocolHandler.setExecutor(Executors.newThreadPerTaskExecutor(factory));
        };
    }
}
