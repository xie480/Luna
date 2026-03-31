package org.yilena.luna.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.yilena.luna.service.AuthService;
import org.yilena.luna.utils.AuthContextHolder;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("Authorization");
        if (!authService.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":\"unauthorized\",\"message\":\"unauthorized\"}");
            return false;
        }

        String jti = authService.extractJti(token);
        String subject = authService.extractSubject(token);
        if (jti == null || jti.isBlank() || subject == null || subject.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":\"unauthorized\",\"message\":\"invalid token\"}");
            return false;
        }

        String principalKey = subject.trim().toLowerCase();
        AuthContextHolder.setSessionId(jti);
        AuthContextHolder.setPrincipalKey(principalKey);
        request.setAttribute("SESSION_ID", jti);
        request.setAttribute("PRINCIPAL_KEY", principalKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContextHolder.clear();
    }
}
