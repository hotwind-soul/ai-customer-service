package com.example.aics.state;

import com.example.aics.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StateStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private StateStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        values = ops;
        when(redis.opsForValue()).thenReturn(values);
        store = new StateStore(redis, mock(ConfigService.class));
    }

    @Test
    void redisUnavailableDoesNotBreakBookingReads() {
        when(redis.hasKey("booked:conv-1")).thenThrow(new RuntimeException("Redis unavailable"));
        when(values.get("booking:conv-1")).thenThrow(new RuntimeException("Redis unavailable"));

        assertThat(store.isBooked("conv-1")).isFalse();
        assertThat(store.getBooking("conv-1")).isNull();
    }

    @Test
    void redisUnavailableDoesNotBreakBusinessStateWrites() {
        when(redis.delete("booking:conv-1")).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatCode(() -> store.removeBooking("conv-1")).doesNotThrowAnyException();
    }
}
