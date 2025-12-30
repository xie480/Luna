package org.yilena.runa;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Slf4j
@EnableAsync
@MapperScan("org.yilena.runa.mapper")
@EnableCaching
@EnableLogRecord(tenant = "RunaApplication")
@SpringBootApplication
@EnableTransactionManagement
public class RunaApplication {
    static void main(String[] args) {
        SpringApplication.run(RunaApplication.class, args);
    }
}
