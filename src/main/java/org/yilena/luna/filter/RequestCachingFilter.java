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
        // Skip SSE stream endpoint to avoid wrapping long-lived event stream requests.
        return uri.contains(ApiPathConstants.LUNA_STATUS_STREAM);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        filterChain.doFilter(wrappedRequest, response);
    }
}
