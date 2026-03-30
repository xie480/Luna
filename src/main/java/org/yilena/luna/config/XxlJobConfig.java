package org.yilena.luna.config; // define package

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor; // import dependency
import lombok.extern.slf4j.Slf4j; // import dependency
import org.slf4j.Logger; // import dependency
import org.slf4j.LoggerFactory; // import dependency
import org.springframework.beans.factory.annotation.Value; // import dependency
import org.springframework.context.annotation.Bean; // import dependency
import org.springframework.context.annotation.Configuration; // import dependency

/*
    xxl-job任务调度配置 // business logic
 */
@Slf4j // declare annotation
@Configuration // declare annotation
public class XxlJobConfig { // define class

    @Value("${xxl-job.admin.addresses}") // declare annotation
    private String adminAddresses; // business logic

    @Value("${xxl-job.executor.application-name}") // declare annotation
    private String appName; // business logic

    @Value("${xxl-job.executor.ip}") // declare annotation
    private String ip; // business logic

    @Value("${xxl-job.executor.port}") // declare annotation
    private int port; // business logic

    @Value("${xxl-job.access-token}") // declare annotation
    private String accessToken; // business logic

    @Value("${xxl-job.executor.log-retention-days}") // declare annotation
    private int logRetentionDays; // business logic

    @Bean // declare annotation
    public XxlJobSpringExecutor xxlJobExecutor() { // method definition
        log.info("<==========xxl-job executor init==========>"); // assignment or init
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor(); // assignment or init
        executor.setAdminAddresses(adminAddresses); // business logic
        executor.setAppname(appName); // business logic
        executor.setIp(ip); // business logic
        executor.setPort(port); // business logic
        executor.setAccessToken(accessToken); // business logic
        executor.setLogRetentionDays(logRetentionDays); // business logic
        return executor; // return result
    } // block end
} // block end
