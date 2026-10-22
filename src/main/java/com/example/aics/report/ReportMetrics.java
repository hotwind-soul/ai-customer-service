package com.example.aics.report;

import com.example.aics.config.AdminDataScope;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运营指标统一口径，页面、趋势与导出共用。 */
final class ReportMetrics {
    private final JdbcTemplate jdbc;

    ReportMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Query(String sql, Object[] args) {}

    private Query cohort(LocalDate from, LocalDate to) {
        AdminDataScope.Condition scope = AdminDataScope.condition("c");
        List<Object> args = new ArrayList<>(List.of(from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        args.addAll(scope.args());
        String sql = """
                WITH bounds AS (SELECT ?::timestamp AS start_at, ?::timestamp AS end_at),
                scoped AS (SELECT c.* FROM conversation c WHERE 1=1
                """ + (scope.restricted() ? " AND " + scope.sql() : "") + """
                ), period_users AS (
                    SELECT c.conversation_id,c.user_id,c.channel,c.tenant_id,c.kf_account_id,m.create_time
                    FROM message m JOIN scoped c ON c.conversation_id=m.conversation_id CROSS JOIN bounds b
                    WHERE m.role='user' AND m.create_time>=b.start_at AND m.create_time<b.end_at
                ), active AS (
                    SELECT DISTINCT conversation_id,user_id,channel,tenant_id,kf_account_id FROM period_users
                ), customers AS (
                    SELECT DISTINCT user_id,channel,tenant_id,kf_account_id FROM active
                    WHERE NULLIF(btrim(user_id),'') IS NOT NULL AND user_id<>'anonymous'
                ), leads AS (
                    SELECT c.* FROM customers c WHERE EXISTS (
                        SELECT 1 FROM appointment a CROSS JOIN bounds b
                        WHERE a.user_id=c.user_id AND a.tenant_id IS NOT DISTINCT FROM c.tenant_id
                            AND a.kf_account_id IS NOT DISTINCT FROM c.kf_account_id
                            AND a.create_time>=b.start_at AND a.create_time<b.end_at
                            AND btrim(a.phone) ~ '^1[3-9][0-9]{9}$'
                            AND NULLIF(btrim(a.project),'') IS NOT NULL
                            AND a.status IN ('new','contacted','closed','done')
                    )
                ), tracking AS (
                    SELECT started_at FROM report_metric_tracking WHERE metric='handoff'
                ), observed AS (
                    SELECT DISTINCT p.conversation_id FROM period_users p CROSS JOIN tracking t
                    WHERE p.create_time>=t.started_at
                ), events AS (
                    SELECT e.* FROM conversation_handoff_event e JOIN observed o USING(conversation_id)
                    CROSS JOIN bounds b CROSS JOIN tracking t
                    WHERE e.create_time>=GREATEST(b.start_at,t.started_at) AND e.create_time<b.end_at
                ), answers AS (
                    SELECT m.match_type,m.decision_trace->>'knowledgeStatus' AS knowledge_status
                    FROM message m JOIN scoped c ON c.conversation_id=m.conversation_id CROSS JOIN bounds b
                    WHERE m.role='assistant' AND m.create_time>=b.start_at AND m.create_time<b.end_at
                ), timings AS (
                    SELECT r.first_response_ms FROM ai_response_timing r
                    JOIN scoped c ON c.conversation_id=r.conversation_id CROSS JOIN bounds b
                    WHERE r.received_at>=b.start_at AND r.received_at<b.end_at
                )
                """;
        return new Query(sql, args.toArray());
    }

    Map<String, Object> overview(LocalDate from, LocalDate to) {
        Query q = cohort(from, to);
        Map<String, Object> data = new LinkedHashMap<>(jdbc.queryForMap(q.sql() + """
                SELECT
                    (SELECT count(*) FROM active) AS "conversations",
                    (SELECT count(*) FROM period_users) AS "userMessages",
                    (SELECT count(*) FROM customers) AS "consultingCustomers",
                    (SELECT count(*) FROM leads) AS "leadCustomers",
                    (SELECT count(*) FROM observed) AS "handoffObservedConversations",
                    (SELECT count(DISTINCT conversation_id) FROM events) AS "handoffs",
                    (SELECT to_char(GREATEST(t.started_at,b.start_at),'YYYY-MM-DD HH24:MI:SS')
                        FROM tracking t CROSS JOIN bounds b) AS "handoffTrackedFrom",
                    (SELECT b.start_at<t.started_at FROM tracking t CROSS JOIN bounds b) AS "handoffPartial",
                    (SELECT count(*) FROM answers) AS "assistantMessages",
                    (SELECT count(*) FROM answers WHERE match_type IS DISTINCT FROM 'ERROR'
                        AND knowledge_status IN ('IN_SCOPE','KNOWLEDGE_GAP')) AS "knowledgeEligibleReplies",
                    (SELECT count(*) FROM answers WHERE match_type='REFUSAL'
                        AND knowledge_status='KNOWLEDGE_GAP') AS "knowledgeGapReplies",
                    (SELECT count(*) FROM answers WHERE match_type='REFUSAL'
                        AND knowledge_status='OUT_OF_SCOPE') AS "outOfScopeReplies",
                    (SELECT count(*) FROM answers WHERE match_type='REFUSAL'
                        AND knowledge_status='BUSINESS_UNAVAILABLE') AS "businessUnavailableReplies",
                    (SELECT count(*) FROM answers WHERE knowledge_status='SAFETY') AS "safetyReplies",
                    (SELECT count(*) FROM answers WHERE match_type='ERROR') AS "errorReplies",
                    (SELECT count(*) FROM answers WHERE match_type IS DISTINCT FROM 'ERROR'
                        AND COALESCE(knowledge_status,'UNKNOWN') NOT IN
                        ('IN_SCOPE','KNOWLEDGE_GAP','OUT_OF_SCOPE','BUSINESS_UNAVAILABLE','SAFETY')) AS "unclassifiedReplies",
                    (SELECT count(*) FROM timings) AS "responseSamples",
                    (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY first_response_ms) FROM timings) AS "firstResponseMedianMs",
                    (SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY first_response_ms) FROM timings) AS "firstResponseP95Ms"
                """, q.args()));
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("leadRate", rate(data, "leadCustomers", "consultingCustomers"));
        data.put("handoffRate", rate(data, "handoffs", "handoffObservedConversations"));
        data.put("knowledgeGapRate", rate(data, "knowledgeGapReplies", "knowledgeEligibleReplies"));
        data.put("composition", jdbc.queryForList(q.sql() + """
                SELECT COALESCE(match_type,'UNKNOWN') AS type,count(*) AS count FROM answers
                GROUP BY match_type ORDER BY count DESC,type
                """, q.args()));
        data.put("handoffReasons", jdbc.queryForList(q.sql() + """
                SELECT reason,count(*) AS count FROM events GROUP BY reason ORDER BY count DESC,reason
                """, q.args()));
        return data;
    }

    List<Map<String, Object>> daily(LocalDate from, LocalDate to) {
        Query q = cohort(from, to);
        return jdbc.queryForList(q.sql() + """
                , days AS (
                    SELECT generate_series(start_at::date,(end_at-interval '1 day')::date,interval '1 day') AS day
                    FROM bounds
                ), activity AS (
                    SELECT create_time::date AS day,count(DISTINCT conversation_id) AS conversations,
                        count(*) AS user_messages FROM period_users GROUP BY create_time::date
                ), handoffs AS (
                    SELECT create_time::date AS day,count(DISTINCT conversation_id) AS handoffs
                    FROM events GROUP BY create_time::date
                )
                SELECT d.day,COALESCE(a.conversations,0) AS conversations,
                    COALESCE(a.user_messages,0) AS user_messages,
                    CASE WHEN d.day+interval '1 day'<=t.started_at THEN NULL ELSE COALESCE(h.handoffs,0) END AS handoffs
                FROM days d CROSS JOIN tracking t
                LEFT JOIN activity a ON a.day=d.day LEFT JOIN handoffs h ON h.day=d.day
                ORDER BY d.day
                """, q.args());
    }

    Map<String, Object> realtime() {
        AdminDataScope.Condition scope = AdminDataScope.condition("c");
        return jdbc.queryForMap("""
                SELECT count(*) AS "queueWaiting",
                    count(*) FILTER (WHERE c.queue_time<=now()-interval '10 minutes') AS "queueOverdue",
                    to_char(clock_timestamp(),'YYYY-MM-DD HH24:MI:SS') AS "updatedAt"
                FROM conversation c WHERE c.status='need_human'
                """ + (scope.restricted() ? " AND " + scope.sql() : ""), scope.args().toArray());
    }

    private static Double rate(Map<String, Object> data, String numerator, String denominator) {
        long total = ((Number) data.get(denominator)).longValue();
        return total == 0 ? null : Math.round(((Number) data.get(numerator)).doubleValue() * 1000 / total) / 10.0;
    }
}
