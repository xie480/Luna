package org.yilena.luna.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置类负责注册 Swagger/OpenAPI 文档分组和基础元数据，便于开发阶段查看接口说明。
 */
@Configuration
public class SwaggerConfig {

    /**
     * 注册 Luna 服务的 Swagger 分组，统一暴露当前应用下的全部接口路径。
     */
    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("Luna服务接口")
                .pathsToMatch("/**")
                .build();
    }

    /**
     * 配置 OpenAPI 基础信息，包括标题、说明、版本和联系方式。
     */
    @Bean
    public OpenAPI springShopOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Luna 接口文档")
                        .description("Luna 服务接口文档")
                        .version("v1")
                        .contact(new Contact().name("yilena"))
                        .license(new License().name("Apache 2.0")
                                .url("http://springdoc.org")));
    }
}
