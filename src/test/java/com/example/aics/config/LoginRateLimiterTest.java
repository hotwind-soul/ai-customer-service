package com.example.aics.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginRateLimiterTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redis.opsForValue()).thenReturn(values);
        limiter = new LoginRateLimiter(redis);
    }

    @Test
    void blocksAccountAndIpOnFifthFailure() {
        when(values.increment(anyString())).thenReturn(
                1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L);

        assertThat(limiter.recordFailure("admin", "127.0.0.1")).isFalse();
        assertThat(limiter.recordFailure("admin", "127.0.0.1")).isFalse();
        assertThat(limiter.recordFailure("admin", "127.0.0.1")).isFalse();
        assertThat(limiter.recordFailure("admin", "127.0.0.1")).isFalse();
        assertThat(limiter.recordFailure("admin", "127.0.0.1")).isTrue();
    }

    @Test
    void successfulLoginClearsAccountSpecificCounter() {
        limiter.recordSuccess("admin", "127.0.0.1");
        verify(redis).delete(anyString());
    }
}
