package org.yilena.runa.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    swagger配置
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("Luna服务接口")
                .pathsToMatch("/org/yilena/luna/**")
                .build();
    }

    @Bean
    public OpenAPI springShopOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Luna接口文档")
                        .description("Luna接口文档")
                        .version("v1")
                        .contact(new Contact().name("yilena"))
                        .license(new License().name("Apache 2.0")
                                .url("http://springdoc.org")));
    }
}