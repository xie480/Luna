package org.yilena.luna.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置类负责初始化 XXL-Job 执行器，将调度中心地址、执行器信息和日志策略注入到任务框架中。
 */
@Slf4j
@Configuration
public class XxlJobConfig {

    /**
     * XXL-Job 调度中心地址。
     */
    @Value("${xxl-job.admin.addresses}")
    private String adminAddresses;

    /**
     * 执行器应用名称。
     */
    @Value("${xxl-job.executor.application-name}")
    private String appName;

    /**
     * 执行器注册 IP。
     */
    @Value("${xxl-job.executor.ip}")
    private String ip;

    /**
     * 执行器监听端口。
     */
    @Value("${xxl-job.executor.port}")
    private int port;

    /**
     * 调度访问令牌。
     */
    @Value("${xxl-job.access-token}")
    private String accessToken;

    /**
     * 作业日志保留天数。
     */
    @Value("${xxl-job.executor.log-retention-days}")
    private int logRetentionDays;

    /**
     * 创建 XXL-Job 执行器实例，统一注入调度中心连接信息和本地执行器配置。
     */
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info("<==========xxl-job executor init==========>");
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
