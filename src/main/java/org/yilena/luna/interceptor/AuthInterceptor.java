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
//        String token = request.getHeader("Authorization");
//        if (!authService.validateToken(token)) {
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            response.setContentType("application/json;charset=UTF-8");
//            response.getWriter().write("{\"error\": \"未授权，请先登录\"}");
//            return false;
//        }
//
//        String jti = authService.extractJti(token);
//        if (jti == null || jti.isBlank()) {
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            response.setContentType("application/json;charset=UTF-8");
//            response.getWriter().write("{\"error\": \"token无效或已过期\"}");
//            return false;
//        }
//
//        // 将 JWT jti 作为本次请求的稳定 sessionId
//        AuthContextHolder.setSessionId(jti);
//        request.setAttribute("SESSION_ID", jti);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContextHolder.clear();
    }
}
