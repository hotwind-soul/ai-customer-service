package com.example.aics.channel;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 微信客服链路的轻量运行指标。
 *
 * 只保存计数、时间和截断后的错误类型，不保存客户标识、消息正文、密钥或 access_token。
 * 计数随应用重启清零；平台返回的送达失败仍会另外写入 audit_log，便于长期追溯。
 */
@Component
public class WeComRuntimeMonitor {

    private final Instant startedAt = Instant.now();
    private final AtomicLong callbacksReceived = new AtomicLong();
    private final AtomicLong callbacksAccepted = new AtomicLong();
    private final AtomicLong callbacksRejected = new AtomicLong();
    private final AtomicLong callbackProcessingFailures = new AtomicLong();
    private final AtomicLong syncRuns = new AtomicLong();
    private final AtomicLong syncFailures = new AtomicLong();
    private final AtomicLong syncedItems = new AtomicLong();
    private final AtomicLong duplicateItems = new AtomicLong();
    private final AtomicLong outboundAccepted = new AtomicLong();
    private final AtomicLong outboundFailures = new AtomicLong();
    private final AtomicLong platformSendFailures = new AtomicLong();
    private final AtomicLong messageLimitTruncations = new AtomicLong();
    private final AtomicLong handoffRequests = new AtomicLong();
    private final AtomicLong handoffSuccesses = new AtomicLong();
    private final AtomicLong handoffFailures = new AtomicLong();
    private final AtomicLong serviceStateSyncs = new AtomicLong();
    private final AtomicLong serviceStateSyncFailures = new AtomicLong();
    private final ConcurrentMap<String, TenantCallbackMetrics> tenantCallbacks = new ConcurrentHashMap<>();

    private volatile Instant lastCallbackAt;
    private volatile Instant lastSyncSuccessAt;
    private volatile Instant lastSyncFailureAt;
    private volatile Instant lastOutboundSuccessAt;
    private volatile Instant lastOutboundFailureAt;
    private volatile Instant lastTokenCheckAt;
    private volatile Boolean lastTokenCheckPassed;
    private volatile String lastError = "";
    private volatile String lastTokenCheckMessage = "尚未验证";

    public void callbackReceived() {
        callbacksReceived.incrementAndGet();
        lastCallbackAt = Instant.now();
    }

    /** 已识别到企业的回调；同时计入全局和该企业的当前进程指标。 */
    public void callbackReceived(String tenantCode) {
        callbackReceived();
        TenantCallbackMetrics metrics = tenantMetrics(tenantCode);
        metrics.received.incrementAndGet();
        metrics.lastReceivedAt = Instant.now();
    }

    public void callbackAccepted() {
        callbacksAccepted.incrementAndGet();
    }

    public void callbackAccepted(String tenantCode) {
        callbackAccepted();
        TenantCallbackMetrics metrics = tenantMetrics(tenantCode);
        metrics.accepted.incrementAndGet();
        metrics.lastAcceptedAt = Instant.now();
    }

    public void callbackRejected(String reason) {
        callbacksRejected.incrementAndGet();
        rememberError(reason);
    }

    public void callbackRejected(String tenantCode, String reason) {
        callbackRejected(reason);
        TenantCallbackMetrics metrics = tenantMetrics(tenantCode);
        metrics.rejected.incrementAndGet();
    }

    public void callbackProcessingFailed(String reason) {
        callbackProcessingFailures.incrementAndGet();
        rememberError(reason);
    }

    public void syncStarted() {
        syncRuns.incrementAndGet();
    }

    public void syncSucceeded(int itemCount) {
        if (itemCount > 0) syncedItems.addAndGet(itemCount);
        lastSyncSuccessAt = Instant.now();
    }

    public void syncFailed(String reason) {
        syncFailures.incrementAndGet();
        lastSyncFailureAt = Instant.now();
        rememberError(reason);
    }

    public void duplicateItem() {
        duplicateItems.incrementAndGet();
    }

    public void outboundAccepted() {
        outboundAccepted.incrementAndGet();
        lastOutboundSuccessAt = Instant.now();
    }

    public void outboundFailed(String reason) {
        outboundFailures.incrementAndGet();
        lastOutboundFailureAt = Instant.now();
        rememberError(reason);
    }

    public void platformSendFailed(String reason) {
        platformSendFailures.incrementAndGet();
        rememberError(reason);
    }

    public void messageLimitTruncated() {
        messageLimitTruncations.incrementAndGet();
    }

    public void handoffRequested() { handoffRequests.incrementAndGet(); }

    public void handoffSucceeded() { handoffSuccesses.incrementAndGet(); }

    public void handoffFailed(String reason) {
        handoffFailures.incrementAndGet();
        rememberError(reason);
    }

    public void serviceStateSyncStarted() { serviceStateSyncs.incrementAndGet(); }

    public void serviceStateSyncSucceeded() { }

    public void serviceStateSyncFailed(String reason) {
        serviceStateSyncFailures.incrementAndGet();
        rememberError(reason);
    }

    public void tokenCheck(boolean passed, String message) {
        lastTokenCheckAt = Instant.now();
        lastTokenCheckPassed = passed;
        lastTokenCheckMessage = safe(message);
        if (!passed) rememberError(message);
    }

    public Map<String, Object> snapshot(Map<String, Object> queue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startedAt", startedAt.toString());
        result.put("callbacksReceived", callbacksReceived.get());
        result.put("callbacksAccepted", callbacksAccepted.get());
        result.put("callbacksRejected", callbacksRejected.get());
        result.put("callbackProcessingFailures", callbackProcessingFailures.get());
        result.put("syncRuns", syncRuns.get());
        result.put("syncFailures", syncFailures.get());
        result.put("syncedItems", syncedItems.get());
        result.put("duplicateItems", duplicateItems.get());
        result.put("outboundAccepted", outboundAccepted.get());
        result.put("outboundFailures", outboundFailures.get());
        result.put("platformSendFailures", platformSendFailures.get());
        result.put("messageLimitTruncations", messageLimitTruncations.get());
        result.put("handoffRequests", handoffRequests.get());
        result.put("handoffSuccesses", handoffSuccesses.get());
        result.put("handoffFailures", handoffFailures.get());
        result.put("serviceStateSyncs", serviceStateSyncs.get());
        result.put("serviceStateSyncFailures", serviceStateSyncFailures.get());
        result.put("lastCallbackAt", iso(lastCallbackAt));
        result.put("lastSyncSuccessAt", iso(lastSyncSuccessAt));
        result.put("lastSyncFailureAt", iso(lastSyncFailureAt));
        result.put("lastOutboundSuccessAt", iso(lastOutboundSuccessAt));
        result.put("lastOutboundFailureAt", iso(lastOutboundFailureAt));
        result.put("lastError", lastError);
        result.put("queue", queue == null ? Map.of() : queue);
        result.put("tokenCheck", tokenCheckSnapshot());
        result.put("callbackScope", "allTenants");
        result.put("resetsOnRestart", true);
        return result;
    }

    /**
     * 返回指定企业的回调状态。队列和发送等指标仍是全局值，回调计数与时间只属于该企业，
     * 避免多企业场景下把其他企业的成功回调误判为当前企业已连通。
     */
    public Map<String, Object> snapshotForTenant(String tenantCode, Map<String, Object> queue) {
        Map<String, Object> result = snapshot(queue);
        TenantCallbackMetrics metrics = tenantCallbacks.get(normalizeTenantCode(tenantCode));
        result.put("callbacksReceived", metrics == null ? 0L : metrics.received.get());
        result.put("callbacksAccepted", metrics == null ? 0L : metrics.accepted.get());
        result.put("callbacksRejected", metrics == null ? 0L : metrics.rejected.get());
        result.put("lastCallbackAt", metrics == null ? "" : iso(metrics.lastReceivedAt));
        result.put("lastAcceptedCallbackAt", metrics == null ? "" : iso(metrics.lastAcceptedAt));
        result.put("callbackScope", "tenant");
        return result;
    }

    private Map<String, Object> tokenCheckSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tested", lastTokenCheckPassed != null);
        result.put("passed", Boolean.TRUE.equals(lastTokenCheckPassed));
        result.put("checkedAt", iso(lastTokenCheckAt));
        result.put("message", lastTokenCheckMessage);
        return result;
    }

    private void rememberError(String value) {
        lastError = safe(value);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "未知异常";
        String normalized = value.replaceAll("(?i)(access[_-]?token|secret|aes[_-]?key)\\s*[=:]\\s*[^\\s,;]+", "$1=***")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
    }

    private String iso(Instant value) {
        return value == null ? "" : value.toString();
    }

    private TenantCallbackMetrics tenantMetrics(String tenantCode) {
        return tenantCallbacks.computeIfAbsent(normalizeTenantCode(tenantCode), ignored -> new TenantCallbackMetrics());
    }

    private String normalizeTenantCode(String tenantCode) {
        return tenantCode == null ? "" : tenantCode.trim().toLowerCase();
    }

    private static final class TenantCallbackMetrics {
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private volatile Instant lastReceivedAt;
        private volatile Instant lastAcceptedAt;
    }
}
