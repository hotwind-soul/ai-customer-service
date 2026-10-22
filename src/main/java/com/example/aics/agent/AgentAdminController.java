package com.example.aics.agent;

import com.example.aics.audit.AuditService;
import com.example.aics.config.WebAuthCookieService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 坐席账号管理接口（受管理鉴权保护，仅管理员可用）
 * GET  /agents                  坐席列表
 * POST /agents                  新增客服坐席及企业/客服号服务范围
 * PUT  /agents/{id}/password    重置密码 {"password":"新密码"}
 * PUT  /agents/{id}/data-scope  修改企业/客服号服务范围
 */
@RestController
@RequestMapping("/agents")
public class AgentAdminController {

    private final AgentAccountService agentAccountService;
    private final AuditService auditService;
    private final WebAuthCookieService authCookies;

    public AgentAdminController(AgentAccountService agentAccountService, AuditService auditService,
                                WebAuthCookieService authCookies) {
        this.agentAccountService = agentAccountService;
        this.auditService = auditService;
        this.authCookies = authCookies;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        return agentAccountService.listAgents(page, pageSize);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        if (body.get("username") == null || body.get("password") == null) {
            return Map.of("ok", false, "error", "用户名和密码必填");
        }
        requireReauthentication(body, request);
        String password = confirmedPassword(body);
        long id = agentAccountService.createAgent(text(body.get("username")), text(body.get("name")),
                password, longList(body.get("tenantIds")), longList(body.get("kfAccountIds")));
        auditService.log("CREATE_AGENT", "账号=" + body.get("username") + "，坐席ID=" + id,
                operator(request));
        return Map.of("ok", true, "id", id);
    }

    @PutMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        requireReauthentication(body, request);
        String password = confirmedPassword(body);
        agentAccountService.resetPassword(id, password);
        auditService.log("RESET_PASSWORD", "坐席ID=" + id, operator(request));
        return Map.of("ok", true);
    }

    @PutMapping("/{id}/data-scope")
    public Map<String, Object> updateDataScope(@PathVariable long id,
                                               @RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        requireReauthentication(body, request);
        agentAccountService.setAgentDataScope(id,
                longList(body.get("tenantIds")), longList(body.get("kfAccountIds")));
        auditService.log("UPDATE_AGENT_DATA_SCOPE", "坐席ID=" + id, operator(request));
        return Map.of("ok", true);
    }

    private void requireReauthentication(Map<String, Object> body, HttpServletRequest request) {
        if (!agentAccountService.verifyCurrentPassword(authCookies.adminToken(request),
                text(body.get("currentPassword")))) {
            throw new IllegalArgumentException("二次验证失败，请输入当前管理员密码");
        }
    }

    private String confirmedPassword(Map<String, Object> body) {
        String password = text(body.get("password"));
        if (password.isBlank() || !password.equals(text(body.get("confirmPassword")))) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        return password;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(item -> {
            if (item instanceof Number number) return number.longValue();
            try { return Long.parseLong(String.valueOf(item)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("服务范围 ID 格式不正确"); }
        }).toList();
    }

    private String operator(HttpServletRequest request) {
        Map<String, Object> session = agentAccountService.adminSession(authCookies.adminToken(request));
        return session == null ? "admin" : String.valueOf(session.get("username"));
    }
}
