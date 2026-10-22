package com.example.aics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 为网页和 API 统一增加浏览器安全响应头。 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(self), geolocation=(self), microphone=()");
        response.setHeader("Content-Security-Policy",
                // 当前三个前端页面使用 Vue 的浏览器运行时编译 DOM 模板，必须允许 eval。
                // 若后续接入构建工具预编译模板，应移除 unsafe-eval 并继续收紧 inline 脚本。
                "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data: blob:; connect-src 'self' ws: wss:; object-src 'none'; " +
                        "base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        filterChain.doFilter(request, response);
    }
}
