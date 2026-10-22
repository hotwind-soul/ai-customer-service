package com.example.aics.chat;

import java.time.LocalDateTime;
import java.util.UUID;

/** 入站时开始计时；异步回复显式传递，重试沿用同一采样标识。 */
public record ResponseTiming(String sampleKey, LocalDateTime receivedAt, long startedNanos) {
    public static ResponseTiming start() {
        return start(UUID.randomUUID().toString());
    }

    public static ResponseTiming start(String sampleKey) {
        return new ResponseTiming(sampleKey, LocalDateTime.now(), System.nanoTime());
    }

    public long elapsedMs() {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
