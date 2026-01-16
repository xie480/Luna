package org.yilena.runa.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class SwaggerStartupPrinter implements ApplicationRunner {

    private final Environment environment;

    public SwaggerStartupPrinter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String port = environment.getProperty("server.port", "8001");
        String contextPath = environment.getProperty("server.servlet.context-path", "");

        System.out.println();
        System.out.println("=================================================");
        System.out.println("🚀 Luna启动成功");
        System.out.println();
        System.out.println("📘 Swagger UI:");
        System.out.println("   http://localhost:" + port + contextPath + "/swagger-ui/index.html");
        System.out.println();
        System.out.println("📄 OpenAPI JSON:");
        System.out.println("   http://localhost:" + port + contextPath + "/v3/api-docs");
        System.out.println();
        System.out.println("📄 接口文档:");
        System.out.println("   http://localhost:" + port + "/doc.html#/home");
        System.out.println("=================================================");
        System.out.println();
    }
}
