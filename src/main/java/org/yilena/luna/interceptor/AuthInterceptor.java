package org.yilena.luna.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.yilena.luna.constants.AuthConstants;
import org.yilena.luna.constants.HttpConstants;
import org.yilena.luna.service.AuthService;
import org.yilena.luna.utils.AuthContextHolder;

/**
 * 认证拦截器，负责校验请求令牌并在请求上下文中写入会话和主体标识。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 鉴权服务，用于校验 token 并提取声明信息。
     */
    @Autowired
    private AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /**
         * 先从请求头提取令牌并校验合法性，不通过时直接返回未授权响应。
         */
        String token = request.getHeader(HttpConstants.HEADER_AUTHORIZATION);
        if (!authService.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(HttpConstants.CONTENT_TYPE_JSON_UTF8);
            response.getWriter().write(AuthConstants.UNAUTHORIZED_RESPONSE_JSON);
            return false;
        }

        /**
         * 令牌通过校验后提取会话标识和主体标识，缺失关键声明同样视为非法令牌。
         */
        String jti = authService.extractJti(token);
        String subject = authService.extractSubject(token);
        if (jti == null || jti.isBlank() || subject == null || subject.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(HttpConstants.CONTENT_TYPE_JSON_UTF8);
            response.getWriter().write(AuthConstants.INVALID_TOKEN_RESPONSE_JSON);
            return false;
        }

        /**
         * 将会话信息写入线程上下文和请求属性，供后续业务链路直接复用。
         */
        String principalKey = subject.trim().toLowerCase();
        AuthContextHolder.setSessionId(jti);
        AuthContextHolder.setPrincipalKey(principalKey);
        request.setAttribute(AuthConstants.REQUEST_ATTR_SESSION_ID, jti);
        request.setAttribute(AuthConstants.REQUEST_ATTR_PRINCIPAL_KEY, principalKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        /**
         * 请求完成后清理线程上下文，避免会话信息串到后续请求。
         */
        AuthContextHolder.clear();
    }
}
