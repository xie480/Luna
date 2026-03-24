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

import java.io.IOException;

/**
 * 请求缓存过滤器
 * 用于包装 HttpServletRequest，以便在后续处理（如异常处理）中可以重复读取 Request Body
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@WebFilter(filterName = "requestCachingFilter", urlPatterns = "/*")
public class RequestCachingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 跳过 SSE 接口，避免长连接被缓存包装导致异常
        return uri.contains("/luna/api/status/stream");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        // 包装请求以缓存 Body
        // 注意：ContentCachingRequestWrapper 只有在输入流被读取后才会缓存内容
        // 如果 Controller 中使用了 @RequestBody，Spring 会读取输入流，此时内容会被缓存
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        filterChain.doFilter(wrappedRequest, response);
    }
}
