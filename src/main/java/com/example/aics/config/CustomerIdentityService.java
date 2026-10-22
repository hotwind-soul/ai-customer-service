package com.example.aics.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网页客户身份由服务端签发并保存在 HttpOnly Cookie 中。
 * 客户端请求体、查询参数和 localStorage 中的 userId 都不再作为身份依据。
 */
@Service
public class CustomerIdentityService {

    public static final String COOKIE_NAME = "AICS_CUSTOMER";
    public static final String REQUEST_ATTRIBUTE = CustomerIdentityService.class.getName() + ".userId";
    private static final Pattern VALID_ID = Pattern.compile("u_web_[0-9a-f]{32}");
    private static final Duration MAX_AGE = Duration.ofDays(365);

    public String resolve(HttpServletRequest request, HttpServletResponse response) {
        Object existing = request.getAttribute(REQUEST_ATTRIBUTE);
        if (existing instanceof String value && VALID_ID.matcher(value).matches()) {
            return value;
        }
        String userId = readCookie(request.getCookies());
        if (userId == null) {
            userId = newUserId();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(userId, isSecure(request)).toString());
        }
        request.setAttribute(REQUEST_ATTRIBUTE, userId);
        return userId;
    }

    public String readCookie(Cookie[] cookies) {
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> VALID_ID.matcher(v).matches())
                .findFirst().orElse(null);
    }

    public String newUserId() {
        return "u_web_" + UUID.randomUUID().toString().replace("-", "");
    }

    public ResponseCookie cookie(String userId, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, userId)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(MAX_AGE)
                .build();
    }

    public ResponseCookie expiredCookie(boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    public boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) return true;
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null && "https".equalsIgnoreCase(forwarded.split(",")[0].trim());
    }
}
