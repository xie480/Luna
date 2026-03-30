package org.yilena.luna;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Slf4j
@EnableAsync
@MapperScan("org.yilena.luna.mapper")
@EnableCaching
@EnableLogRecord(tenant = "RunaApplication")
@SpringBootApplication(exclude = {RedissonAutoConfigurationV2.class})
@EnableTransactionManagement
/**
 * RunaApplication ??
 */
public class RunaApplication {
    public static void main(String[] args) {
        SpringApplication.run(RunaApplication.class, args);
    }
}
