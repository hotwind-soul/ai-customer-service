package com.example.aics.chat;

import com.example.aics.config.ConfigService;
import com.example.aics.image.ImageService;
import com.example.aics.message.LinkCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ConversationServiceTest {

    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ListOperations<String, String> redisLists;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> ops = mock(ListOperations.class);
        redisLists = ops;
        when(redis.opsForList()).thenReturn(redisLists);
        service = new ConversationService(jdbc, redis,
                mock(ConfigService.class), mock(ImageService.class));
    }

    @Test
    void closedConversationIsNeverReusedForNewMessages() {
        when(jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE conversation_id = ? AND user_id = ? AND status <> 'closed'",
                Integer.class, "closed-1", "user-1")).thenReturn(0);

        String next = service.ensureConversation("closed-1", "user-1");

        assertThat(next).isNotEqualTo("closed-1").isNotBlank();
        verify(jdbc).update(eq("INSERT INTO conversation(conversation_id, user_id) VALUES (?, ?)"),
                eq(next), eq("user-1"));
    }

    @Test
    void kfConversationReusesQueuedOrHumanConversation() {
        when(jdbc.queryForList(
                "SELECT conversation_id FROM conversation " +
                        "WHERE user_id = ? AND channel = 'kf' AND status IN ('active','need_human','human') " +
                        "ORDER BY update_time DESC, id DESC LIMIT 1",
                String.class, "user-1")).thenReturn(List.of("kf-existing"));

        assertThat(service.findOrCreateKfConversation("user-1")).isEqualTo("kf-existing");
    }

    @Test
    void sameCustomerInTwoKfAccountsNeverReusesConversation() {
        String sql = "SELECT conversation_id FROM conversation WHERE user_id=? AND channel='kf' " +
                "AND tenant_id=? AND kf_account_id=? " +
                "AND status IN ('active','need_human','human') " +
                "ORDER BY update_time DESC, id DESC LIMIT 1";
        when(jdbc.queryForList(sql, String.class, "user-1", 7L, 11L)).thenReturn(List.of());
        when(jdbc.queryForList(sql, String.class, "user-1", 7L, 12L)).thenReturn(List.of());

        String first = service.findOrCreateKfConversation("user-1", 7L, 11L);
        String second = service.findOrCreateKfConversation("user-1", 7L, 12L);

        assertThat(first).isNotEqualTo(second);
        verify(jdbc).update(
                "INSERT INTO conversation(conversation_id, user_id, channel, tenant_id, kf_account_id) " +
                        "VALUES (?, ?, 'kf', ?, ?)", first, "user-1", 7L, 11L);
        verify(jdbc).update(
                "INSERT INTO conversation(conversation_id, user_id, channel, tenant_id, kf_account_id) " +
                        "VALUES (?, ?, 'kf', ?, ?)", second, "user-1", 7L, 12L);
    }

    @Test
    void linkCardIsPersistedWithTheAssistantMessage() {
        LinkCard card = new LinkCard("昆明首渡店", "昆明市官渡区星耀路99号",
                "https://apis.map.qq.com/uri/v1/marker?marker=test",
                "/assets/tencent-map-card.svg");

        service.saveLinkCards(42L, List.of(card));

        verify(jdbc).update(
                "INSERT INTO message_link_card(message_id, title, description, url, thumbnail_url) " +
                        "VALUES (?,?,?,?,?)",
                42L, "昆明首渡店", "昆明市官渡区星耀路99号",
                "https://apis.map.qq.com/uri/v1/marker?marker=test",
                "/assets/tencent-map-card.svg");
    }

    @Test
    void redis写入失败_消息仍成功落库并返回Id() {
        when(jdbc.queryForObject(
                "INSERT INTO message(conversation_id, role, content, match_type) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "conv-1", "user", "你好", null)).thenReturn(42L);
        when(redisLists.range("hist:conv-1", 0, -1))
                .thenThrow(new RuntimeException("Redis unavailable"));

        long messageId = assertDoesNotThrow(() -> service.saveMessage(
                "conv-1", "user", "你好", null));

        assertThat(messageId).isEqualTo(42L);
        verify(jdbc).update(
                "UPDATE conversation SET update_time = now() WHERE conversation_id = ?", "conv-1");
    }

    @Test
    void redis读取失败_从数据库返回历史() {
        when(redisLists.range("hist:conv-1", 0, -1))
                .thenThrow(new RuntimeException("Redis unavailable"));
        when(jdbc.queryForList(anyString(), eq("conv-1"), anyInt())).thenReturn(List.of(
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "assistant", "content", "您好")));

        String history = service.recentHistoryText("conv-1");

        assertThat(history).isEqualTo("用户：你好\n客服：您好\n");
    }

    @Test
    void 路由追踪作为Json保存在助手消息上() {
        String trace = "{\"route\":\"FACT\",\"authoritySource\":\"PROJECT_CENTER\"}";

        service.saveDecisionTrace(42L, trace);

        verify(jdbc).update(
                "UPDATE message SET decision_trace=CAST(? AS jsonb) WHERE id=?",
                trace, 42L);
    }
}
