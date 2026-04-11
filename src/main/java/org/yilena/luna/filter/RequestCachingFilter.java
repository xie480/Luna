package org.yilena.luna.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.yilena.luna.constants.ApiPathConstants;

import java.io.IOException;

/**
 * 请求缓存过滤器，负责包装普通 HTTP 请求体，便于后续异常处理或日志链路重复读取请求内容。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@WebFilter(filterName = "requestCachingFilter", urlPatterns = "/*")
public class RequestCachingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        /**
         * SSE 长连接请求不做包装，避免对持续输出流造成额外干扰。
         */
        String uri = request.getRequestURI();
        return uri.contains(ApiPathConstants.LUNA_STATUS_STREAM);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        /**
         * 为普通请求包裹可缓存的 RequestWrapper，使请求体可被后续链路重复读取。
         */
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        filterChain.doFilter(wrappedRequest, response);
    }
}
