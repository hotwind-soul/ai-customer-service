package com.example.aics.channel;

/** 当前企微消息的企业/客服号上下文，供知识、图片和门店查询做数据隔离。 */
public final class TenantExecutionContext {

    public record Scope(long tenantId, Long kfAccountId) {}

    public interface Lease extends AutoCloseable {
        @Override void close();
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantExecutionContext() {}

    public static Scope current() {
        return CURRENT.get();
    }

    public static Lease use(long tenantId, Long kfAccountId) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(tenantId, kfAccountId));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }
}
