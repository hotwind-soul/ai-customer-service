package com.example.aics.channel;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 微信客服上线前自检与运行状态汇总。 */
@Service
public class WeComDiagnosticsService {

    private final WeComKfTokenService tokenService;
    private final WeComKfApiClient apiClient;
    private final WeComCryptoService cryptoService;
    private final WeComRuntimeMonitor monitor;
    private final WeComKfService kfService;

    public WeComDiagnosticsService(WeComKfTokenService tokenService,
                                   WeComKfApiClient apiClient,
                                   WeComCryptoService cryptoService,
                                   WeComRuntimeMonitor monitor,
                                   WeComKfService kfService) {
        this.tokenService = tokenService;
        this.apiClient = apiClient;
        this.cryptoService = cryptoService;
        this.monitor = monitor;
        this.kfService = kfService;
    }

    /** 多企业凭证自检：只使用指定企业的密钥，绝不回退到旧版全局配置。 */
    public Map<String, Object> diagnostics(WeComTenantService.TenantCredentials tenant,
                                           List<Map<String, Object>> accounts,
                                           String publicBaseUrl) {
        String callbackUrl = normalizeBase(publicBaseUrl) + "/kf/callback/" + tenant.code();
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("enabled", "企业已启用", tenant.enabled(),
                tenant.enabled() ? "允许接收回调并调用企微接口" : "请先启用该企业"));
        checks.add(check("corpId", "企业 ID", !tenant.corpId().isBlank(), "企微管理后台 → 我的企业"));
        checks.add(check("secret", "接口调用应用 Secret", !tenant.secret().isBlank(),
                "必须属于已在微信客服中授权的自建应用"));
        checks.add(check("callbackToken", "回调 Token", !tenant.callbackToken().isBlank(),
                "须与该企业接收消息配置完全一致"));
        checks.add(check("aesKey", "EncodingAESKey", tenant.aesKey().length() == 43,
                tenant.aesKey().isBlank() ? "尚未配置" : "要求 43 位，当前 " + tenant.aesKey().length() + " 位"));
        checks.add(check("crypto", "回调加解密可用", cryptoService.isConfigured(tenant),
                "依赖企业 ID、回调 Token 和 43 位 EncodingAESKey"));
        long enabledAccounts = accounts == null ? 0 : accounts.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("enabled"))).count();
        checks.add(check("accounts", "至少一个已启用客服号", enabledAccounts > 0,
                enabledAccounts > 0 ? "已启用 " + enabledAccounts + " 个客服号" : "请添加并启用微信客服号"));
        boolean https = callbackUrl.startsWith("https://");
        boolean publicHost = !(callbackUrl.contains("127.0.0.1") || callbackUrl.contains("localhost"));
        checks.add(check("https", "回调地址格式", https && publicHost,
                https && publicHost ? callbackUrl + "（仅检查格式，最终以企微后台保存验证为准）"
                        : "生产环境需通过公网 HTTPS 域名访问"));

        List<WeComKfApiClient.KfAccountProfile> remoteAccounts = List.of();
        String apiFailure = "企业未启用或 CorpID/Secret 未完整配置";
        if (tenant.enabled() && tenant.apiConfigured()) {
            try {
                remoteAccounts = apiClient.listAccounts(tenant);
                apiFailure = "";
            } catch (Exception e) {
                apiFailure = safeFailure(e.getMessage());
            }
        }
        boolean apiPassed = apiFailure.isBlank();
        checks.add(check("apiPermission", "微信客服接口权限", apiPassed,
                apiPassed ? "已成功读取企微客服账号，共 " + remoteAccounts.size() + " 个"
                        : apiFailure));

        Set<String> remoteIds = new HashSet<>();
        remoteAccounts.forEach(account -> remoteIds.add(account.openKfId()));
        List<String> missingEnabled = accounts == null ? List.of() : accounts.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("enabled")))
                .map(item -> String.valueOf(item.getOrDefault("openKfid", "")))
                .filter(openKfId -> !remoteIds.contains(openKfId))
                .toList();
        boolean accountMatch = apiPassed && enabledAccounts > 0 && missingEnabled.isEmpty();
        String accountDetail = !apiPassed ? "接口权限验证通过后才能核对客服号"
                : enabledAccounts == 0 ? "请先同步或添加客服号，再手动启用需要接入的账号"
                : missingEnabled.isEmpty() ? "全部已启用客服号均可由当前应用调用"
                : "企微返回列表中未找到：" + String.join("、", missingEnabled);
        checks.add(check("accountMatch", "客服号归属核对", accountMatch, accountDetail));

        long passed = checks.stream().filter(item -> Boolean.TRUE.equals(item.get("passed"))).count();
        Map<String, Object> runtime = monitor.snapshotForTenant(tenant.code(), kfService.executorStatus());
        long acceptedCallbacks = runtime.get("callbacksAccepted") instanceof Number value
                ? value.longValue() : 0L;
        boolean checksPassed = passed == checks.size();
        String overall = !checksPassed ? "incomplete" : acceptedCallbacks > 0 ? "connected" : "configured";
        String statusLabel = switch (overall) {
            case "connected" -> "已连接";
            case "configured" -> "配置已通过";
            default -> "待完善";
        };
        String nextAction = switch (overall) {
            case "connected" -> "已收到该企业的有效回调，可继续同步并启用需要接待的客服号。";
            case "configured" -> "请将回调地址、Token 和 EncodingAESKey 保存到企微后台，再向客服号发送一条测试消息。";
            default -> "请先处理下方未通过的检查项，然后重新执行一键检查。";
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.id());
        result.put("tenantCode", tenant.code());
        result.put("overall", overall);
        result.put("statusLabel", statusLabel);
        result.put("nextAction", nextAction);
        result.put("summary", passed + "/" + checks.size() + " 项自动检查通过");
        result.put("callbackUrl", callbackUrl);
        result.put("checks", checks);
        result.put("runtime", runtime);
        return result;
    }

    public Map<String, Object> verifyToken(WeComTenantService.TenantCredentials tenant) {
        try {
            tokenService.refreshAndVerify(tenant);
            List<WeComKfApiClient.KfAccountProfile> accounts = apiClient.listAccounts(tenant);
            String message = "凭证和微信客服接口权限有效，已读取 " + accounts.size() + " 个客服号";
            monitor.tokenCheck(true, "企业 " + tenant.code() + " " + message);
            return Map.of("passed", true, "message", message, "accountCount", accounts.size());
        } catch (Exception e) {
            monitor.tokenCheck(false, e.getMessage());
            return Map.of("passed", false, "message", safeFailure(e.getMessage()));
        }
    }

    private Map<String, Object> check(String key, String label, boolean passed, String detail) {
        return Map.of("key", key, "label", label, "passed", passed, "detail", detail);
    }

    private String normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "http://127.0.0.1:8080";
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String safeFailure(String message) {
        if (message == null || message.isBlank()) return "企微凭证验证失败";
        String normalized = message.replaceAll("(?i)(access[_-]?token|secret)\\s*[=:]\\s*[^\\s,;]+", "$1=***")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
    }
}
