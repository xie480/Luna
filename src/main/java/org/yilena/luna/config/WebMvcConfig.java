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

/**
 * 该配置类负责统一 Spring MVC 的静态资源、内容协商和鉴权拦截规则。
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 认证拦截器，用于校验需要登录态的请求。
     */
    @Autowired
    private AuthInterceptor authInterceptor;

    /**
     * 注册 Swagger 相关静态资源映射，确保接口文档页面能够被正常访问。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("<==========开始配置静态资源映射========>");
        registry.addResourceHandler(ApiPathConstants.SWAGGER_UI_HTML).addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler(ApiPathConstants.WEBJARS_ALL).addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * 配置默认返回内容类型为 JSON，并保留对请求 Accept 头的识别能力。
     */
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer
                .defaultContentType(MediaType.APPLICATION_JSON)
                .ignoreAcceptHeader(false);
    }

    /**
     * 注册全局鉴权拦截器，并放行登录、状态流和 Swagger 文档等无需鉴权的路径。
     */
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
