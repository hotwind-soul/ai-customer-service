package com.example.aics.agent;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 不活跃用户唤醒：每天早上 9 点扫描"超过 N 天没说话"的用户，
 * 生成唤醒任务（含 AI 个性化回访话术建议），客服照着清单回访。
 *
 * 设计说明：网页没有主动触达客户的通道，真正的外发要等企微接入；
 * 现阶段系统负责"找准人 + 写好话术"，人工负责送达（电话/微信）。
 * 企微接入后这里只需把"生成任务"换成"企微群发"，数据链路不用动。
 *
 * 可配置项（热更新）：
 *   wakeup.inactiveDays  几天不说话算不活跃（默认 3）
 *   wakeup.enabled       是否开启每日扫描（默认 false；客户运营由外部业务系统负责）
 */
@Service
public class WakeupService {

    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final ModelGateway modelGateway;
    private final WakeupTaskCreator taskCreator;

    public WakeupService(JdbcTemplate jdbc, ConfigService config, ModelGateway modelGateway,
                         WakeupTaskCreator taskCreator) {
        this.jdbc = jdbc;
        this.config = config;
        this.modelGateway = modelGateway;
        this.taskCreator = taskCreator;
    }

    /** 每天 09:00 自动扫描（cron：秒 分 时 日 月 周） */
    @Scheduled(cron = "0 0 9 * * ?", zone = "Asia/Shanghai")
    public void dailyScan() {
        if (!"true".equals(config.get("wakeup.enabled", "false"))) return;
        scan();
    }

    /** 扫描不活跃用户并生成任务（也可手动触发：POST /wakeup/run） */
    public int scan() {
        int inactiveDays = (int) config.getDouble("wakeup.inactiveDays", 3);

        // 找出"超过 N 天没说话、且当前没有待办唤醒任务"的用户
        // 待办 = new（未送达）/ delivered（已送达、客服未回访）；contacted（已回访）的用户隔 N 天会再次被唤醒
        // make_interval(days => ?) 参数化，避免字符串拼接（P2-27）
        List<Map<String, Object>> inactive = jdbc.queryForList(
                "SELECT c.user_id, max(c.update_time) AS last_active, " +
                "(array_agg(c.tenant_id ORDER BY c.update_time DESC))[1] AS tenant_id, " +
                "(array_agg(c.kf_account_id ORDER BY c.update_time DESC))[1] AS kf_account_id " +
                "FROM conversation c " +
                "WHERE c.user_id != 'anonymous' " +
                "GROUP BY c.user_id " +
                "HAVING max(c.update_time) < now() - make_interval(days => ?) " +
                "  AND NOT EXISTS (SELECT 1 FROM wake_task w WHERE w.user_id = c.user_id " +
                "                   AND w.status IN ('new', 'delivered')) " +
                "ORDER BY last_active LIMIT 50",   // W25-Nit：单次最多 50 人，防止用户量大时定时任务一次跑太久
                inactiveDays);

        int created = 0;
        for (Map<String, Object> u : inactive) {
            String userId = String.valueOf(u.get("user_id"));
            String profile = getProfile(userId);
            String script = generateScript(profile);
            Long tenantId = u.get("tenant_id") instanceof Number number ? number.longValue() : null;
            Long kfAccountId = u.get("kf_account_id") instanceof Number number ? number.longValue() : null;
            if (taskCreator.createIfAbsent(userId, u.get("last_active"), profile, script,
                    tenantId, kfAccountId)) {
                created++;
            }
        }
        return created;
    }

    private String getProfile(String userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT profile FROM user_profile WHERE user_id = ?", String.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    // ==================== P2-11 批量唤醒运营 ====================

    /**
     * 圈人预览：按项目关键词（项目名 + 别名自动扩展）圈出最近 90 天咨询过、
     * 且已沉默 inactiveDays 天以上的网页客户；排除当前有待办与冷却期内的用户。
     * @param project 项目名（支持模糊，如"眼袋"会命中"去眼袋"等已发布别名）
     */
    public List<Map<String, Object>> candidates(String project, Integer inactiveDays, int limit) {
        List<String> keywords = projectKeywords(project);
        if (keywords.isEmpty()) return List.of();
        int days = inactiveDays == null || inactiveDays < 1 ? 3 : Math.min(inactiveDays, 60);
        int cap = Math.min(Math.max(limit, 1), 200);
        int cooldown = cooldownDays();
        String kwChain = String.join(" OR ", java.util.Collections.nCopies(keywords.size(), "m.content ILIKE ?"));
        List<Object> args = new ArrayList<>();
        keywords.forEach(k -> args.add("%" + k + "%"));
        args.add(days);
        args.add(cooldown);
        args.add(cap);
        String sql = "SELECT c.user_id, max(c.update_time) AS last_active, " +
                "(array_agg(c.tenant_id ORDER BY c.update_time DESC))[1] AS tenant_id, " +
                "(array_agg(c.kf_account_id ORDER BY c.update_time DESC))[1] AS kf_account_id, " +
                "(array_agg(fq.content ORDER BY c.update_time DESC))[1] AS first_question " +
                "FROM conversation c " +
                "JOIN message m ON m.conversation_id = c.conversation_id AND m.role = 'user' " +
                "LEFT JOIN LATERAL (SELECT content FROM message q WHERE q.conversation_id = c.conversation_id " +
                "  AND q.role = 'user' ORDER BY q.create_time, q.id LIMIT 1) fq ON TRUE " +
                "WHERE c.user_id != 'anonymous' AND c.channel = 'web' " +
                "  AND c.create_time >= now() - interval '90 days' " +
                "  AND (" + kwChain + ") " +
                "GROUP BY c.user_id " +
                "HAVING max(c.update_time) < now() - make_interval(days => ?) " +
                "  AND NOT EXISTS (SELECT 1 FROM wake_task w WHERE w.user_id = c.user_id " +
                "    AND w.status IN ('new','delivered')) " +
                "  AND NOT EXISTS (SELECT 1 FROM wake_task w2 WHERE w2.user_id = c.user_id " +
                "    AND w2.create_time >= now() - make_interval(days => ?)) " +
                "ORDER BY last_active DESC LIMIT ?";
        return jdbc.queryForList(sql, args.toArray());
    }

    /**
     * 批量生成唤醒任务：统一话术 + 冷却期频控（wakeup.cooldownDays，默认 7 天）。
     * @return {created, skipped, cooldownDays}
     */
    public Map<String, Object> createBatch(List<String> userIds, String script) {
        int cooldown = cooldownDays();
        String safeScript = script == null || script.isBlank()
                ? "您好，我是昇媛美容的客服。近期咱们感兴趣的项目有优惠活动，方便的话可以来店免费咨询看看～"
                : script.trim().substring(0, Math.min(script.trim().length(), 2000));
        int created = 0;
        int skipped = 0;
        for (String raw : userIds) {
            String userId = raw == null ? "" : raw.trim();
            if (userId.isEmpty() || "anonymous".equals(userId)) { skipped++; continue; }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT max(c.update_time) AS last_active, " +
                    "(array_agg(c.tenant_id ORDER BY c.update_time DESC))[1] AS tenant_id, " +
                    "(array_agg(c.kf_account_id ORDER BY c.update_time DESC))[1] AS kf_account_id " +
                    "FROM conversation c WHERE c.user_id = ? AND c.channel = 'web'", userId);
            if (rows.isEmpty()) { skipped++; continue; }
            Map<String, Object> info = rows.get(0);
            Long tenantId = info.get("tenant_id") instanceof Number number ? number.longValue() : null;
            Long kfAccountId = info.get("kf_account_id") instanceof Number number ? number.longValue() : null;
            if (taskCreator.createManual(userId, info.get("last_active"), safeScript,
                    tenantId, kfAccountId, cooldown)) {
                created++;
            } else {
                skipped++;
            }
        }
        return Map.of("created", created, "skipped", skipped, "cooldownDays", cooldown);
    }

    /** 批量冷却天数（同一用户 N 天内不重复唤醒），热更新配置。 */
    public int cooldownDays() {
        return (int) config.getDouble("wakeup.cooldownDays", 7);
    }

    /** 项目关键词扩展：项目名 + 已发布别名（命中任意一个即视为咨询过该项目）。 */
    private List<String> projectKeywords(String project) {
        String kw = project == null ? "" : project.trim();
        if (kw.isEmpty()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(kw);
        jdbc.query(
                "SELECT sp.name AS name, spa.alias AS alias FROM service_project sp " +
                        "LEFT JOIN service_project_alias spa ON spa.project_id = sp.id " +
                        "WHERE sp.status = 'published' AND (sp.name ILIKE ? OR spa.alias ILIKE ?)",
                resultSet -> {
                    while (resultSet.next()) {
                        String name = resultSet.getString(1);
                        String alias = resultSet.getString(2);
                        if (name != null && !name.isBlank()) set.add(name.trim());
                        if (alias != null && !alias.isBlank()) set.add(alias.trim());
                    }
                    return null;
                }, "%" + kw + "%", "%" + kw + "%");
        return new ArrayList<>(set);
    }

    /** 让 AI 按画像写一段个性化回访话术（失败就用通用模板，不阻塞） */
    private String generateScript(String profile) {
        try {
            String prompt = """
                    你是医美机构的资深客服。请根据这位客户的画像，写一段 2~3 句的电话/微信回访话术：
                    温和亲切、先关心再提活动、提到他之前关心的项目、结尾约到店。
                    画像为空就写通用版本（提周年庆活动即可）。只输出话术本身。

                    客户画像：
                    %s
                    """.formatted(profile.isBlank() ? "（无画像，新客户）" : profile);
            return modelGateway.chat(prompt).trim();
        } catch (Exception e) {
            return "您好，我是昇媛美容的客服。咱们周年庆活动还在继续，您之前咨询的项目现在有优惠，方便的话可以来店免费检测看看～";
        }
    }
}
