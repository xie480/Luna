package org.yilena.runa.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.runa.constants.SymbolConstant;
import org.yilena.runa.utils.ServiceCommunicateUtil;

/*
    程序应用初始化类
 */
@Component
@RequiredArgsConstructor
public class ApplicationInitConfig {

    @PostConstruct
    public void symbolInit() {
    }
}
