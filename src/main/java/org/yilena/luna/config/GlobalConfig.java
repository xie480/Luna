package org.yilena.luna.config;

import com.baomidou.mybatisplus.autoconfigure.DdlApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 该配置类负责在应用启动时触发 MyBatis-Plus 的 DDL 初始化流程，确保数据库结构按配置自动准备。
 */
@Component
public class GlobalConfig {

    /**
     * 注册 DDL 启动执行器，在 Spring Boot 启动阶段统一执行数据库建表或结构校验逻辑。
     */
    @Bean
    public DdlApplicationRunner ddlApplicationRunner(@Autowired(required = false) List ddlLrist) {
        return new DdlApplicationRunner(ddlLrist);
    }
}
