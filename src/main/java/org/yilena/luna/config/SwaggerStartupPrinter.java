package org.yilena.luna.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
/**
 * SwaggerStartupPrinter ??
 */
public class SwaggerStartupPrinter implements ApplicationRunner {

    private final Environment environment; // 声明成员字段

    public SwaggerStartupPrinter(Environment environment) { // 定义方法签名
        this.environment = environment; // 执行赋值操作
    } // 结束当前代码块

    @Override // 声明注解
    public void run(ApplicationArguments args) { // 定义方法签名
        String port = environment.getProperty("server.port", "8001"); // 执行赋值操作
        String contextPath = environment.getProperty("server.servlet.context-path", ""); // 执行赋值操作

        System.out.println(); // 执行语句逻辑
        System.out.println("================================================="); // 执行赋值操作
        System.out.println("🚀 Luna启动成功"); // 执行语句逻辑
        System.out.println(); // 执行语句逻辑
        System.out.println("📘 Swagger UI:"); // 执行语句逻辑
        System.out.println("   http://localhost:" + port + contextPath + "/swagger-ui/index.html"); // 执行语句逻辑
        System.out.println(); // 执行语句逻辑
        System.out.println("📄 OpenAPI JSON:"); // 执行语句逻辑
        System.out.println("   http://localhost:" + port + contextPath + "/v3/api-docs"); // 执行语句逻辑
        System.out.println(); // 执行语句逻辑
        System.out.println("📄 接口文档:"); // 执行语句逻辑
        System.out.println("   http://localhost:" + port + contextPath + "/swagger-ui.html"); // 执行语句逻辑
        System.out.println("================================================="); // 执行赋值操作
        System.out.println(); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
