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

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader(HttpConstants.HEADER_AUTHORIZATION);
        if (!authService.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(HttpConstants.CONTENT_TYPE_JSON_UTF8);
            response.getWriter().write(AuthConstants.UNAUTHORIZED_RESPONSE_JSON);
            return false;
        }

        String jti = authService.extractJti(token);
        String subject = authService.extractSubject(token);
        if (jti == null || jti.isBlank() || subject == null || subject.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(HttpConstants.CONTENT_TYPE_JSON_UTF8);
            response.getWriter().write(AuthConstants.INVALID_TOKEN_RESPONSE_JSON);
            return false;
        }

        String principalKey = subject.trim().toLowerCase();
        AuthContextHolder.setSessionId(jti);
        AuthContextHolder.setPrincipalKey(principalKey);
        request.setAttribute(AuthConstants.REQUEST_ATTR_SESSION_ID, jti);
        request.setAttribute(AuthConstants.REQUEST_ATTR_PRINCIPAL_KEY, principalKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContextHolder.clear();
    }
}
