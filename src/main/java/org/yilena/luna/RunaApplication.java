package org.yilena.luna;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 项目启动入口类，负责启用缓存、异步、调度、事务和日志记录等基础框架能力。
 */
@Slf4j
@EnableAsync
@EnableScheduling
@MapperScan({"org.yilena.luna.mapper", "org.yilena.luna.prompt.governance.mapper"})
@EnableCaching
@EnableLogRecord(tenant = "RunaApplication")
@SpringBootApplication(exclude = {RedissonAutoConfigurationV2.class})
@EnableTransactionManagement
public class RunaApplication {

    public static void main(String[] args) {
        /**
         * 启动 Spring Boot 应用并加载当前项目的全部自动配置与组件。
         */
        SpringApplication.run(RunaApplication.class, args);
    }
}
