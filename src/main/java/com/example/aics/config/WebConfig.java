package com.example.aics.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：
 * 1. 把本地上传目录映射成可访问的 URL（/uploads/** → uploads/ 目录）；
 * 2. 注册拦截器：管理端口令校验 + 聊天接口限流（第 11 周安全加固）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(AdminAuthInterceptor adminAuthInterceptor,
                     RateLimitInterceptor rateLimitInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理端：后台管理接口需要口令（admin.html 页面本身不拦截，只是个空壳，数据都在接口里）
        // 例外：/wakeup/mine（客户查自己的唤醒任务）和 /wakeup/*/read（客户标记已读）放行给客户侧
        // /api/metrics（模型网关指标）也受管理鉴权保护
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/knowledge/**", "/projects", "/projects/**", "/images/**", "/stores/**",
                        "/config/**", "/appointments/**", "/agents/**",
                        "/admin/**", "/report/**", "/wakeup/**",
                        "/api/conversations/query", "/api/conversations/*", "/api/conversations/*/messages",
                        "/api/metrics", "/api/audit-logs",
                        "/api/wecom/**",
                        "/api/ai-models/**",
                        "/api/resource-scopes",
                        "/api/quality/**",
                        "/api/chat/debug", "/api/chat/debug/**")
                .excludePathPatterns("/wakeup/mine", "/wakeup/*/read");
        // 聊天接口限流（客户侧 WebSocket 走 /ws/chat，不在此列；W26-U1 发图接口也纳入）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/chat/**", "/api/chat-image");
    }
}
