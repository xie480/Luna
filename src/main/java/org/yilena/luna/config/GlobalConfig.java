package org.yilena.luna.config; // define package

import com.baomidou.mybatisplus.autoconfigure.DdlApplicationRunner; // import dependency
import org.springframework.beans.factory.annotation.Autowired; // import dependency
import org.springframework.context.annotation.Bean; // import dependency
import org.springframework.stereotype.Component; // import dependency

import java.util.List; // import dependency

/*
    启动时执行ddl // business logic
 */
@Component // declare annotation
public class GlobalConfig { // define class
    @Bean // declare annotation
    public DdlApplicationRunner ddlApplicationRunner(@Autowired(required = false) List ddlLrist) { // method definition
        return new DdlApplicationRunner(ddlLrist); // return result
    } // block end
} // block end
