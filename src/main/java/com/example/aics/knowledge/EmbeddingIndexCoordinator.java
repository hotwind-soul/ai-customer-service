package com.example.aics.knowledge;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 单实例内协调知识写入与向量模型切换。
 * 普通知识导入/编辑持有共享锁；模型切换从候选向量生成到配置提交全程持有独占锁，
 * 防止已用旧模型生成但尚未落库的向量在切换完成后混入新索引。
 */
@Component
public class EmbeddingIndexCoordinator {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public Lease knowledgeMutation() {
        lock.readLock().lock();
        return () -> lock.readLock().unlock();
    }

    public Lease exclusiveSwitch() {
        lock.writeLock().lock();
        return () -> lock.writeLock().unlock();
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
