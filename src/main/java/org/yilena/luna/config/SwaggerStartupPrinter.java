package org.yilena.luna.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 该启动器用于在应用启动完成后打印 Swagger 和 OpenAPI 访问地址，方便本地联调时快速进入文档页。
 */
@Configuration
public class SwaggerStartupPrinter implements ApplicationRunner {

    /**
     * Spring 环境对象，用于读取当前端口和上下文路径配置。
     */
    private final Environment environment;

    public SwaggerStartupPrinter(Environment environment) {
        this.environment = environment;
    }

    /**
     * 读取运行端口与上下文路径，并输出接口文档访问入口，减少开发阶段的人工拼接成本。
     */
    @Override
    public void run(ApplicationArguments args) {
        String port = environment.getProperty("server.port", "8001");
        String contextPath = environment.getProperty("server.servlet.context-path", "");

        System.out.println();
        System.out.println("=================================================");
        System.out.println("Luna 启动成功");
        System.out.println();
        System.out.println("Swagger UI:");
        System.out.println("   http://localhost:" + port + contextPath + "/swagger-ui/index.html");
        System.out.println();
        System.out.println("OpenAPI JSON:");
        System.out.println("   http://localhost:" + port + contextPath + "/v3/api-docs");
        System.out.println();
        System.out.println("接口文档:");
        System.out.println("   http://localhost:" + port + contextPath + "/swagger-ui.html");
        System.out.println("=================================================");
        System.out.println();
    }
}
