package com.example.aics.chat;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 单实例会话轮次锁：同一客户/会话的请求严格串行，避免并发读取同一份历史、重复执行业务动作。
 * 多实例部署时应替换为带租约的 Redis/数据库分布式锁。
 */
@Component
public class ConversationTurnGuard {

    private static final int STRIPE_COUNT = 256;
    private final Semaphore[] locks = new Semaphore[STRIPE_COUNT];

    public ConversationTurnGuard() {
        for (int i = 0; i < locks.length; i++) locks[i] = new Semaphore(1, true);
    }

    public Lease acquire(String conversationId, String userId) {
        // 优先按客户身份串行：首条请求还没有 conversationId，下一条已有 conversationId，
        // 若分别锁两个键仍会并发。一个客户多会话并行的收益远小于业务动作重复的风险。
        String identity = userId != null && !userId.isBlank()
                ? "user:" + userId
                : "conversation:" + (conversationId == null ? "anonymous" : conversationId);
        Semaphore semaphore = locks[Math.floorMod(identity.hashCode(), locks.length)];
        semaphore.acquireUninterruptibly();
        return new Lease(semaphore);
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean closed;

        private Lease(Semaphore semaphore) { this.semaphore = semaphore; }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            semaphore.release();
        }
    }
}
