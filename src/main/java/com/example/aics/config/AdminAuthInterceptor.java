package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 后台分级鉴权：super_admin 拥有全部能力；admin 只可访问明确分配的模块；
 * agent 只能使用客服工作台。接口设置和管理员账号管理固定只允许 super_admin。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthInterceptor.class);
    private static final String SUPER_ONLY = "__super_admin_only__";
    private static final String QUALITY_OR_DEBUG = "__quality_or_debug__";
    private static final String KNOWLEDGE_OR_DEBUG = "__knowledge_or_debug__";
    private final AgentAccountService agentAccountService;
    private final WebAuthCookieService authCookies;

    public AdminAuthInterceptor(@Lazy AgentAccountService agentAccountService,
                                WebAuthCookieService authCookies) {
        this.agentAccountService = agentAccountService;
        this.authCookies = authCookies;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String token = authCookies.adminToken(request);
        Map<String, Object> session = agentAccountService.adminSession(token);
        if (session == null || session.isEmpty()) {
            return reject(response, 401, "需要管理员账号登录");
        }
        String role = String.valueOf(session.get("role"));
        request.setAttribute("aics.admin.actor", String.valueOf(session.get("username")));
        request.setAttribute("aics.admin.id", session.get("id"));
        request.setAttribute("aics.admin.role", role);
        request.setAttribute("aics.admin.tenantIds", session.getOrDefault("tenantIds", List.of()));
        request.setAttribute("aics.admin.kfAccountIds", session.getOrDefault("kfAccountIds", List.of()));
        if (AgentAccountService.ROLE_SUPER_ADMIN.equals(role)) {
            return true;
        }
        String required = requiredPermission(request);
        if (required == null) return true;
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) session.getOrDefault("permissions", List.of());
        if (!SUPER_ONLY.equals(required) && (permissions.contains(required)
                || QUALITY_OR_DEBUG.equals(required) && (permissions.contains("quality.manage")
                || permissions.contains("ai.debug"))
                || KNOWLEDGE_OR_DEBUG.equals(required) && (permissions.contains("knowledge.manage")
                || permissions.contains("ai.debug")))) return true;
        log.warn("后台权限拒绝: user={}, uri={}, required={}",
                session.get("username"), request.getRequestURI(), required);
        return reject(response, 403,
                SUPER_ONLY.equals(required) ? "仅超级管理员可以执行此操作" : "当前管理员没有此模块权限");
    }

    private String requiredPermission(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("/admin/session".equals(uri)) return null;
        if ("/api/resource-scopes".equals(uri)) return null;
        if (uri.startsWith("/admin/")) return SUPER_ONLY;
        if (uri.startsWith("/api/wecom/")) return SUPER_ONLY;
        if (uri.startsWith("/api/ai-models")) return SUPER_ONLY;
        if (uri.startsWith("/config/")) {
            String key = uri.substring("/config/".length());
            if ("list".equals(key) || "reload".equals(key)
                    || "embedding-model/switch".equals(key)
                    || "embedding-model/switch/status".equals(key)
                    || KeyConfigService.isSensitiveConfigKey(key)) {
                return SUPER_ONLY;
            }
            return "ai.debug";
        }
        if (uri.startsWith("/report/")) return "report.view";
        if (uri.startsWith("/api/quality/regression-cases")) return QUALITY_OR_DEBUG;
        if (uri.startsWith("/api/quality/")) return "quality.manage";
        if (uri.startsWith("/knowledge/")) return "knowledge.manage";
        if ("/projects/options".equals(uri)) return null;
        if (uri.startsWith("/projects")) return "knowledge.manage";
        if (uri.startsWith("/images/")) return "images.manage";
        if (uri.equals("/images")) return "images.manage";
        if (uri.startsWith("/stores")) return "stores.manage";
        if (uri.startsWith("/agents")) return "agents.manage";
        if (uri.startsWith("/appointments") || uri.startsWith("/wakeup")) return "operations.manage";
        if (uri.startsWith("/api/conversations/")) {
            // 物理删除会话不可恢复：DELETE（单条 {id} 与清空 clear）要求独立删除权限，
            // 避免"只读查看"管理员凭 conversations.view 就能删库里的会话记录。
            return "DELETE".equalsIgnoreCase(request.getMethod())
                    ? "conversations.delete" : "conversations.view";
        }
        if (uri.startsWith("/api/audit-logs")) return "audit.view";
        if ("/api/chat/debug/run".equals(uri)) return KNOWLEDGE_OR_DEBUG;
        if (uri.startsWith("/api/chat/debug") || uri.startsWith("/api/metrics")) return "ai.debug";
        return "operations.manage";
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }
}
