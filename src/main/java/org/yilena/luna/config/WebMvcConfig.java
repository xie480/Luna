package org.yilena.luna.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.yilena.luna.constants.ApiPathConstants;
import org.yilena.luna.interceptor.AuthInterceptor;

/*
    web配置
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("<==========开始设置静态资源映射=========>");
        registry.addResourceHandler(ApiPathConstants.SWAGGER_UI_HTML).addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler(ApiPathConstants.WEBJARS_ALL).addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer
                .defaultContentType(MediaType.APPLICATION_JSON)
                .ignoreAcceptHeader(false);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        ApiPathConstants.AUTH_LOGIN,
                        ApiPathConstants.LUNA_STATUS_STREAM,
                        ApiPathConstants.SWAGGER_UI_HTML,
                        ApiPathConstants.WEBJARS_ALL,
                        ApiPathConstants.SWAGGER_UI_ALL,
                        ApiPathConstants.API_DOCS_ALL
                );
    }
}
