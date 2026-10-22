package com.example.aics.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;

/** 后台与坐席令牌只存 HttpOnly Cookie；请求头继续兼容旧客户端和自动化测试。 */
@Service
public class WebAuthCookieService {

    public static final String ADMIN_COOKIE = "AICS_ADMIN";
    public static final String AGENT_COOKIE = "AICS_AGENT";

    private final ConfigService configService;

    public WebAuthCookieService(ConfigService configService) {
        this.configService = configService;
    }

    /** 登录时长（小时）：从 system_config 读 auth.token_ttl_hours，默认 24，改库立即生效（P1-5）。 */
    private Duration tokenTtl() {
        return Duration.ofHours(configService.getInt("auth.token_ttl_hours", 24));
    }

    public String adminToken(HttpServletRequest request) {
        return token(request, "X-Admin-Token", ADMIN_COOKIE);
    }

    public String agentToken(HttpServletRequest request) {
        return token(request, "X-Agent-Token", AGENT_COOKIE);
    }

    public String websocketAgentToken(org.springframework.web.socket.WebSocketSession session) {
        String cookieHeader = session.getHandshakeHeaders().getFirst(HttpHeaders.COOKIE);
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && AGENT_COOKIE.equals(pair[0])) return pair[1];
        }
        return null;
    }

    public ResponseCookie sessionCookie(String name, String token, boolean secure) {
        return ResponseCookie.from(name, token).httpOnly(true).secure(secure).sameSite("Strict")
                .path("/").maxAge(tokenTtl()).build();
    }

    public ResponseCookie expiredCookie(String name, boolean secure) {
        return ResponseCookie.from(name, "").httpOnly(true).secure(secure).sameSite("Strict")
                .path("/").maxAge(Duration.ZERO).build();
    }

    public boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) return true;
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null && "https".equalsIgnoreCase(forwarded.split(",")[0].trim());
    }

    private String token(HttpServletRequest request, String header, String cookieName) {
        String legacy = request.getHeader(header);
        if (legacy != null && !legacy.isBlank()) return legacy;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies).filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue).filter(v -> !v.isBlank()).findFirst().orElse(null);
    }
}
