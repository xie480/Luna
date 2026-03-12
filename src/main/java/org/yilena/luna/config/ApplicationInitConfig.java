package org.yilena.luna.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
