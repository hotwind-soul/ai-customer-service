package com.example.aics.chat;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆：用户画像。
 *
 * 原理：每积累 6 条用户消息，异步让 DeepSeek 读一遍该用户最近的聊天记录，
 * 提取"城市、意向项目、顾虑点、个人信息"等画像要点存库；
 * 之后每次问答，画像会拼进 Prompt——AI 就能"记住"这位客户上次聊过什么、
 * 是哪个城市的、关心哪个项目，跨会话也不会忘。
 *
 * 成本控制：6 条消息才提取一次，且异步执行不拖慢回复，一次提取约几百 token。
 */
@Service
public class UserProfileService {

    /** 每积累多少条用户消息触发一次画像提取 */
    private static final int EXTRACT_EVERY = 6;

    /**
     * 画像提取专用线程池（常驻单例，不关闭）：core=2 max=4，有界队列 100。
     * 避免占用公共 ForkJoinPool（P2-25），高峰时任务排队而非拖垮公共线程。
     * W25-Nit：拒绝策略用 DiscardPolicy——队列满时丢弃本次提取（画像提取是锦上添花，
     * 下次攒够 6 条会再触发），避免 CallerRunsPolicy 把提取任务回退到 WS 请求线程、
     * 调模型最长 30s 阻塞客户消息。
     */
    private final java.util.concurrent.ExecutorService profileExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(100),
                    new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    private final JdbcTemplate jdbc;
    private final ModelGateway modelGateway;
    private final ConfigService config;

    public UserProfileService(JdbcTemplate jdbc, ModelGateway modelGateway, ConfigService config) {
        this.jdbc = jdbc;
        this.modelGateway = modelGateway;
        this.config = config;
    }

    @PreDestroy
    public void shutdown() {
        profileExecutor.shutdown();
        try {
            if (!profileExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                profileExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            profileExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 取用户画像文本（没有则返回空串，Prompt 里就不拼这段） */
    public String getProfileText(String userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT profile FROM user_profile WHERE user_id = ?",
                String.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    /** 消息计数 +1，到阈值就异步提取画像（每次问答完成后调用） */
    public void onUserMessage(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return;   // 匿名用户不建画像
        }
        jdbc.update(
                "INSERT INTO user_profile(user_id, profile, msg_count) VALUES (?, '', 1) " +
                "ON CONFLICT (user_id) DO UPDATE SET msg_count = user_profile.msg_count + 1",
                userId);
        Integer count = jdbc.queryForObject(
                "SELECT msg_count FROM user_profile WHERE user_id = ?", Integer.class, userId);
        int every = Math.max(1, Math.min(config.getInt("profile.extractEvery", EXTRACT_EVERY), 100));
        if (count != null && count % every == 0) {
            profileExecutor.execute(() -> extractProfileQuietly(userId));
        }
    }

    /** 异步提取画像：读最近聊天记录 → 让模型总结 → 覆盖式更新 */
    private void extractProfileQuietly(String userId) {
        try {
            int historyMessages = Math.max(2, Math.min(config.getInt("profile.historyMessages", 30), 200));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT m.role, m.content FROM message m " +
                    "JOIN conversation c ON m.conversation_id = c.conversation_id " +
                    "WHERE c.user_id = ? ORDER BY m.create_time DESC, m.id DESC LIMIT ?",
                    userId, historyMessages);
            if (rows.isEmpty()) return;

            StringBuilder history = new StringBuilder();
            for (int i = rows.size() - 1; i >= 0; i--) {   // 反转为正序
                history.append(rows.get(i).get("role")).append(": ")
                        .append(rows.get(i).get("content")).append("\n");
            }

            String rules = config.get("profile.prompt", AiConfigCatalog.defaultValue("profile.prompt"));
            String prompt = rules + "\n\n聊天记录：\n" + Desensitizer.mask(history.toString());

            String profile = modelGateway.chat(prompt).trim();
            if (!profile.isBlank()) {
                jdbc.update("UPDATE user_profile SET profile = ?, update_time = now() WHERE user_id = ?",
                        profile, userId);
            }
        } catch (Exception ignored) {
            // 画像提取失败不影响主流程，下次再试
        }
    }
}
