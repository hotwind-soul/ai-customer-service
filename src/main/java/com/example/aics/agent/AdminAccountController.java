package com.example.aics.agent;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminPermissionCatalog;
import com.example.aics.config.WebAuthCookieService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 超级管理员专用的管理员账号、密码和权限分配接口。 */
@RestController
@RequestMapping("/admin")
public class AdminAccountController {

    private final AgentAccountService accounts;
    private final AuditService audit;
    private final WebAuthCookieService authCookies;

    public AdminAccountController(AgentAccountService accounts, AuditService audit,
                                  WebAuthCookieService authCookies) {
        this.accounts = accounts;
        this.audit = audit;
        this.authCookies = authCookies;
    }

    /** 普通管理员和超级管理员都可读取自己的后台身份。 */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        Map<String, Object> session = accounts.adminSession(token(request));
        if (session == null) throw new IllegalArgumentException("后台登录已失效");
        return session;
    }

    /** 可分配权限目录；接口设置和管理员管理不在目录中，固定只属于超级管理员。 */
    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        return Map.of("permissions", AdminPermissionCatalog.asMaps());
    }

    @GetMapping("/accounts")
    public Map<String, Object> list() {
        return Map.of("accounts", accounts.listAdminAccounts());
    }

    @GetMapping("/data-scopes")
    public Map<String, Object> dataScopes() {
        return accounts.adminDataScopeCatalog();
    }

    @PostMapping("/accounts")
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        requireReauthentication(request, body);
        String operator = operator(request);
        String username = text(body.get("username"));
        long id = accounts.createAdminAccount(username, text(body.get("name")),
                confirmedPassword(body), stringList(body.get("permissions")),
                longList(body.get("tenantIds")), longList(body.get("kfAccountIds")));
        audit.log("CREATE_ADMIN", "账号=" + username, operator);
        return Map.of("ok", true, "id", id);
    }

    @PutMapping("/accounts/{id}/profile")
    public Map<String, Object> updateProfile(@PathVariable long id,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        requireReauthentication(request, body);
        String operator = operator(request);
        String username = text(body.get("username"));
        accounts.updateAdminProfile(id, username, text(body.get("name")));
        audit.log("UPDATE_ADMIN_PROFILE", "管理员ID=" + id + "，账号=" + username,
                operator);
        return Map.of("ok", true);
    }

    @PutMapping("/accounts/{id}/permissions")
    public Map<String, Object> updatePermissions(@PathVariable long id,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        requireReauthentication(request, body);
        String operator = operator(request);
        accounts.setAdminPermissions(id, stringList(body.get("permissions")));
        audit.log("UPDATE_ADMIN_PERMISSIONS", "管理员ID=" + id, operator);
        return Map.of("ok", true);
    }

    @PutMapping("/accounts/{id}/data-scope")
    public Map<String, Object> updateDataScope(@PathVariable long id,
                                               @RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        requireReauthentication(request, body);
        String operator = operator(request);
        accounts.setAdminDataScope(id, longList(body.get("tenantIds")),
                longList(body.get("kfAccountIds")));
        audit.log("UPDATE_ADMIN_DATA_SCOPE", "管理员ID=" + id, operator);
        return Map.of("ok", true);
    }

    @PutMapping("/accounts/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        requireReauthentication(request, body);
        String operator = operator(request);
        accounts.resetAdminPassword(id, confirmedPassword(body));
        audit.log("RESET_ADMIN_PASSWORD", "管理员ID=" + id, operator);
        return Map.of("ok", true);
    }

    @PutMapping("/accounts/{id}/enabled")
    public Map<String, Object> setEnabled(@PathVariable long id,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        requireReauthentication(request, body);
        String operator = operator(request);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        accounts.setAdminEnabled(id, enabled);
        audit.log(enabled ? "ENABLE_ADMIN" : "DISABLE_ADMIN", "管理员ID=" + id,
                operator);
        return Map.of("ok", true);
    }

    private String operator(HttpServletRequest request) {
        Map<String, Object> session = accounts.adminSession(token(request));
        return session == null ? "super_admin" : String.valueOf(session.get("username"));
    }

    private String token(HttpServletRequest request) {
        return authCookies.adminToken(request);
    }

    private void requireReauthentication(HttpServletRequest request, Map<String, Object> body) {
        String currentPassword = text(body.get("currentPassword"));
        if (!accounts.verifyCurrentPassword(token(request), currentPassword)) {
            throw new IllegalArgumentException("二次验证失败，请输入当前超级管理员密码");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(item -> {
            if (item instanceof Number number) return number.longValue();
            try { return Long.parseLong(String.valueOf(item)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("数据范围 ID 格式不正确"); }
        }).toList();
    }

    private String confirmedPassword(Map<String, Object> body) {
        String password = text(body.get("password"));
        String confirmation = text(body.get("confirmPassword"));
        if (password.isBlank() || confirmation.isBlank()) {
            throw new IllegalArgumentException("请输入密码并再次确认");
        }
        if (!password.equals(confirmation)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        return password;
    }
}
