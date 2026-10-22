package com.example.aics.channel;

import com.example.aics.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 超级管理员的多企业微信客服配置接口。所有敏感字段只写不回显。 */
@RestController
@RequestMapping("/api/wecom/tenants")
public class WeComTenantController {

    private final WeComTenantService tenants;
    private final WeComDiagnosticsService diagnostics;
    private final WeComKfApiClient apiClient;
    private final AuditService audit;

    public WeComTenantController(WeComTenantService tenants,
                                 WeComDiagnosticsService diagnostics,
                                 WeComKfApiClient apiClient,
                                 AuditService audit) {
        this.tenants = tenants;
        this.diagnostics = diagnostics;
        this.apiClient = apiClient;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        String baseUrl = baseUrl(request);
        List<Map<String, Object>> rows = tenants.listTenants().stream().map(row -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>(row);
            item.put("callbackUrl", baseUrl + "/kf/callback/" + row.get("code"));
            return item;
        }).toList();
        return Map.of("tenants", rows);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        long id = tenants.createTenant(text(body, "code"), text(body, "name"),
                text(body, "corpId"), text(body, "secret"), text(body, "callbackToken"),
                text(body, "aesKey"), bool(body, "enabled", true));
        audit.log("CREATE_WECOM_TENANT", "tenant_id=" + id, actor(request));
        return Map.of("id", id, "result", "企业已创建");
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        tenants.updateTenant(id, text(body, "code"), text(body, "name"), text(body, "corpId"),
                text(body, "secret"), text(body, "callbackToken"), text(body, "aesKey"),
                bool(body, "enabled", true));
        audit.log("UPDATE_WECOM_TENANT", "tenant_id=" + id, actor(request));
        return Map.of("result", "企业已更新");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        tenants.deleteTenant(id);
        audit.log("DELETE_WECOM_TENANT", "tenant_id=" + id, actor(request));
        return Map.of("result", "企业及其客服号已删除");
    }

    @PostMapping("/{tenantId}/accounts")
    public Map<String, Object> createAccount(@PathVariable long tenantId,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        long id = tenants.createAccount(tenantId, text(body, "openKfid"), text(body, "name"),
                text(body, "businessScope"), text(body, "welcomeMessage"),
                bool(body, "enabled", true));
        audit.log("CREATE_WECOM_KF_ACCOUNT", "tenant_id=" + tenantId + " account_id=" + id,
                actor(request));
        return Map.of("id", id, "result", "微信客服号已创建");
    }

    @PutMapping("/{tenantId}/accounts/{accountId}")
    public Map<String, Object> updateAccount(@PathVariable long tenantId,
                                             @PathVariable long accountId,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        tenants.updateAccount(tenantId, accountId, text(body, "openKfid"), text(body, "name"),
                text(body, "businessScope"), text(body, "welcomeMessage"),
                bool(body, "enabled", true));
        audit.log("UPDATE_WECOM_KF_ACCOUNT", "tenant_id=" + tenantId + " account_id=" + accountId,
                actor(request));
        return Map.of("result", "微信客服号已更新");
    }

    @DeleteMapping("/{tenantId}/accounts/{accountId}")
    public Map<String, Object> deleteAccount(@PathVariable long tenantId,
                                             @PathVariable long accountId,
                                             HttpServletRequest request) {
        tenants.deleteAccount(tenantId, accountId);
        audit.log("DELETE_WECOM_KF_ACCOUNT", "tenant_id=" + tenantId + " account_id=" + accountId,
                actor(request));
        return Map.of("result", "微信客服号已删除");
    }

    /** 从企微读取客服号目录并安全合并；不会删除账号，也不会擅自启用新账号。 */
    @PostMapping("/{tenantId}/accounts/sync")
    public Map<String, Object> syncAccounts(@PathVariable long tenantId,
                                             HttpServletRequest request) {
        WeComTenantService.TenantCredentials tenant = tenants.requireTenantById(tenantId, true);
        try {
            List<WeComKfApiClient.KfAccountProfile> remote = apiClient.listAccounts(tenant);
            WeComTenantService.AccountSyncSummary summary =
                    tenants.synchronizeAccounts(tenantId, remote);
            String message = "已从企微读取 " + summary.remoteCount() + " 个客服号：新增 "
                    + summary.inserted() + " 个（默认停用），更新名称 " + summary.renamed()
                    + " 个，本地独有 " + summary.localOnly() + " 个（已保留）";
            audit.log("SYNC_WECOM_KF_ACCOUNTS",
                    "tenant_id=" + tenantId + " remote=" + summary.remoteCount()
                            + " inserted=" + summary.inserted() + " renamed=" + summary.renamed()
                            + " local_only=" + summary.localOnly(), actor(request));
            return Map.of(
                    "result", message,
                    "remoteCount", summary.remoteCount(),
                    "inserted", summary.inserted(),
                    "renamed", summary.renamed(),
                    "unchanged", summary.unchanged(),
                    "localOnly", summary.localOnly());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取企微客服号失败：" + e.getMessage(), e);
        }
    }

    @GetMapping("/{id}/diagnostics")
    public Map<String, Object> diagnostics(@PathVariable long id, HttpServletRequest request) {
        WeComTenantService.TenantCredentials tenant = tenants.requireTenantById(id, false);
        return diagnostics.diagnostics(tenant, tenants.listAccounts(id), baseUrl(request));
    }

    @PostMapping("/{id}/diagnostics/token-check")
    public Map<String, Object> verifyCredentials(@PathVariable long id) {
        return diagnostics.verifyToken(tenants.requireTenantById(id, true));
    }

    private static String actor(HttpServletRequest request) {
        Object value = request.getAttribute("aics.admin.actor");
        return value == null ? "super_admin" : String.valueOf(value);
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String baseUrl(HttpServletRequest request) {
        boolean defaultPort = "https".equalsIgnoreCase(request.getScheme()) && request.getServerPort() == 443
                || "http".equalsIgnoreCase(request.getScheme()) && request.getServerPort() == 80;
        return request.getScheme() + "://" + request.getServerName()
                + (defaultPort ? "" : ":" + request.getServerPort());
    }
}
