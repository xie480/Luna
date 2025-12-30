package org.yilena.runa.config;

import com.baomidou.mybatisplus.autoconfigure.DdlApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

/*
    启动时执行ddl
 */
@Component
public class GlobalConfig {
    @Bean
    public DdlApplicationRunner ddlApplicationRunner(@Autowired(required = false) List ddlLrist) {
        return new DdlApplicationRunner(ddlLrist);
    }
}
