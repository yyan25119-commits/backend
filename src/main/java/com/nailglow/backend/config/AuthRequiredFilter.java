package com.nailglow.backend.config;

import com.nailglow.backend.service.AuthService;
import com.nailglow.backend.service.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class AuthRequiredFilter extends OncePerRequestFilter {
    private final AuthService authService;

    public AuthRequiredFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String requiredRole = requiredRole(request.getRequestURI());
        if (requiredRole == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AuthenticatedUser> user = authService.authenticate(request);
        if (user.isEmpty()) {
            writeAuthError(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
            return;
        }
        if (!requiredRole.equals(user.get().role())) {
            writeAuthError(response, HttpServletResponse.SC_FORBIDDEN, "没有访问权限");
            return;
        }

        request.setAttribute("authUser", user.get());
        filterChain.doFilter(request, response);
    }

    private String requiredRole(String path) {
        if (path.startsWith("/api/admin")) {
            return "admin";
        }
        if (path.startsWith("/api/user") || path.startsWith("/api/agent")) {
            return "user";
        }
        return null;
    }

    private void writeAuthError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
