package com.example.aics.report;

import com.example.aics.config.AdminDataScope;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用的本机 PostgreSQL 验证：仅 pg_temp，所有 DDL/DML 最终回滚。 */
@EnabledIfSystemProperty(named = "aics.test.postgres", matches = "true")
class ReportMetricsSqlTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private ReportMetrics metrics;
    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-08");

    @BeforeEach
    void setUp() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "postgres");
        properties.setProperty("password", databasePassword());
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "15");
        connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/aics", properties);
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("SET LOCAL search_path=pg_temp");
        jdbc.execute("SET LOCAL statement_timeout='10s'");
        jdbc.execute("""
                CREATE TEMP TABLE conversation (
                    conversation_id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64),channel VARCHAR(20) DEFAULT 'web',
                    tenant_id BIGINT,kf_account_id BIGINT,status VARCHAR(20) DEFAULT 'active',agent_id BIGINT,
                    queue_time TIMESTAMP,create_time TIMESTAMP DEFAULT now(),update_time TIMESTAMP DEFAULT now());
                CREATE TEMP TABLE message (
                    id BIGSERIAL PRIMARY KEY,conversation_id VARCHAR(64),role VARCHAR(20),content TEXT DEFAULT '测试消息',
                    match_type VARCHAR(20),decision_trace JSONB,create_time TIMESTAMP DEFAULT now());
                CREATE TEMP TABLE appointment (
                    user_id VARCHAR(64),tenant_id BIGINT,kf_account_id BIGINT,phone VARCHAR(20),project VARCHAR(50),
                    status VARCHAR(20) DEFAULT 'new',create_time TIMESTAMP DEFAULT now());
                """);
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V44__operation_metrics.sql"))
                .replace("CREATE TABLE ", "CREATE TEMP TABLE ")
                .replace("CREATE FUNCTION record_conversation_handoff", "CREATE FUNCTION pg_temp.record_conversation_handoff")
                .replace("EXECUTE FUNCTION record_conversation_handoff", "EXECUTE FUNCTION pg_temp.record_conversation_handoff");
        jdbc.execute(migration);
        jdbc.update("UPDATE report_metric_tracking SET started_at='2026-09-01 00:00:00'");
        metrics = new ReportMetrics(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }

    @Test
    void oldActiveConversationsCountButEmptyAndOutsidePeriodDoNot() {
        conversation("old", "customer", null, null);
        conversation("second", "customer", null, null);
        conversation("empty", "empty-user", null, null);
        conversation("outside", "outside-user", null, null);
        jdbc.update("UPDATE conversation SET create_time='2026-08-01'");
        user("old", "2026-09-01 09:00");
        user("old", "2026-09-08 23:59:59");
        user("second", "2026-09-02 09:00");
        user("outside", "2026-09-09 00:00");
        Map<String, Object> data = overview();
        count(data, "conversations", 2);
        count(data, "userMessages", 3);
        count(data, "consultingCustomers", 1);
        assertThat(metrics.daily(FROM, TO)).hasSize(8);
        count(metrics.daily(FROM, TO).get(0), "conversations", 1);
    }

    @Test
    void leadCustomersAreDeduplicatedAndCannotLeakAcrossTenantsOrAccounts() {
        conversation("a", "same-id", 1L, 11L);
        conversation("a-repeat", "same-id", 1L, 11L);
        conversation("b", "same-id", 2L, 22L);
        conversation("c", "same-id", 1L, 12L);
        for (String id : List.of("a", "a-repeat", "b", "c")) user(id, "2026-09-02");
        lead("same-id", 1L, 11L, "13812345678", "抗衰", "new");
        lead("same-id", 1L, 11L, "13812345678", "抗衰", "new");
        lead("same-id", 2L, 22L, "123", "抗衰", "new");
        lead("same-id", 1L, 12L, "13812345678", "抗衰", "cancelled");
        Map<String, Object> all = overview();
        count(all, "consultingCustomers", 3);
        count(all, "leadCustomers", 1);
        assertThat(all.get("leadRate")).isEqualTo(33.3);
        try (var ignored = AdminDataScope.use(new AdminDataScope.AccessScope(true, List.of(2L), List.of(22L)))) {
            Map<String, Object> scoped = overview();
            count(scoped, "conversations", 1);
            count(scoped, "leadCustomers", 0);
            assertThat(scoped.get("leadRate")).isEqualTo(0.0);
        }
    }

    @Test
    void handoffEventsSurviveClosureAndQueueToHumanIsNotDoubleCounted() {
        conversation("c", "u", null, null);
        user("c", "2026-09-02");
        jdbc.update("UPDATE conversation SET status='need_human'");
        jdbc.update("UPDATE conversation SET status='human',agent_id=4");
        jdbc.update("UPDATE conversation SET status='need_human',agent_id=NULL");
        jdbc.update("UPDATE conversation SET status='closed'");
        jdbc.update("UPDATE conversation_handoff_event SET create_time='2026-09-02',reason='CUSTOMER_REQUEST'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_handoff_event", Integer.class)).isEqualTo(1);
        assertThat(overview().get("handoffRate")).isEqualTo(100.0);
        count(overview(), "handoffs", 1);
        jdbc.update("UPDATE conversation SET status='active'");
        jdbc.update("UPDATE conversation SET status='human'");
        jdbc.update("UPDATE conversation_handoff_event SET create_time='2026-09-02'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_handoff_event", Integer.class)).isEqualTo(2);
        count(overview(), "handoffs", 1);
    }

    @Test
    void missingHistoryIsNullAndPartialPeriodUsesOnlyObservedCohort() {
        jdbc.update("UPDATE report_metric_tracking SET started_at='2026-09-05 12:00'");
        conversation("old", "u-old", null, null);
        user("old", "2026-09-02");
        Map<String, Object> historical = metrics.overview(FROM, LocalDate.parse("2026-09-04"));
        assertThat(historical.get("handoffRate")).isNull();
        assertThat(metrics.daily(FROM, TO).get(0).get("handoffs")).isNull();
        conversation("new", "u-new", null, null);
        user("new", "2026-09-06");
        jdbc.update("UPDATE conversation SET status='need_human' WHERE conversation_id='new'");
        jdbc.update("UPDATE conversation_handoff_event SET create_time='2026-09-06'");
        Map<String, Object> partial = overview();
        count(partial, "conversations", 2);
        count(partial, "handoffObservedConversations", 1);
        assertThat(partial.get("handoffPartial")).isEqualTo(true);
        assertThat(partial.get("handoffRate")).isEqualTo(100.0);
    }

    @Test
    void knowledgeReasonsAndHistoricalUnknownsHaveSeparateDenominators() {
        conversation("c", "u", null, null);
        assistant("FAQ", "IN_SCOPE");
        assistant("REFUSAL", "KNOWLEDGE_GAP");
        assistant("REFUSAL", "OUT_OF_SCOPE");
        assistant("REFUSAL", "BUSINESS_UNAVAILABLE");
        assistant("SAFETY", "SAFETY");
        assistant("REFUSAL", null);
        assistant("ERROR", "IN_SCOPE");
        Map<String, Object> data = overview();
        count(data, "assistantMessages", 7);
        count(data, "knowledgeEligibleReplies", 2);
        count(data, "unclassifiedReplies", 1);
        count(data, "errorReplies", 1);
        assertThat(data.get("knowledgeGapRate")).isEqualTo(50.0);
    }

    @Test
    void responsePercentilesUseRecordedSamplesAndRespectScopeAndTime() {
        conversation("a", "u", 1L, 11L);
        conversation("b", "u", 2L, 22L);
        jdbc.update("INSERT INTO ai_response_timing VALUES ('one','a','2026-09-02',100),('two','a','2026-09-02',500)," +
                "('other-tenant','b','2026-09-02',9000),('outside','a','2026-09-09',9000)");
        try (var ignored = AdminDataScope.use(new AdminDataScope.AccessScope(true, List.of(1L), List.of(11L)))) {
            Map<String, Object> data = overview();
            count(data, "responseSamples", 2);
            assertThat(((Number) data.get("firstResponseMedianMs")).doubleValue()).isEqualTo(300.0);
            assertThat(((Number) data.get("firstResponseP95Ms")).doubleValue()).isEqualTo(480.0);
        }
    }

    @Test
    void redeliveryKeepsFirstSuccessfulSampleWithoutOverwritingIt() {
        conversation("c", "u", null, null);
        var conversations = new com.example.aics.chat.ConversationService(jdbc,
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                org.mockito.Mockito.mock(com.example.aics.config.ConfigService.class),
                org.mockito.Mockito.mock(com.example.aics.image.ImageService.class));
        var first = new com.example.aics.chat.ResponseTiming("same-message", FROM.atStartOfDay(), System.nanoTime());
        conversations.recordFirstResponse("c", first);
        Long duration = jdbc.queryForObject("SELECT first_response_ms FROM ai_response_timing", Long.class);
        conversations.recordFirstResponse("c", new com.example.aics.chat.ResponseTiming(
                "same-message", TO.atStartOfDay(), System.nanoTime()-9_000_000_000L));
        count(overview(), "responseSamples", 1);
        assertThat(jdbc.queryForObject("SELECT first_response_ms FROM ai_response_timing", Long.class)).isEqualTo(duration);
    }

    @Test
    void emptyRatesAreUnavailableAndRealtimeQueueIgnoresReportDates() {
        Map<String, Object> empty = overview();
        for (String key : List.of("leadRate", "handoffRate", "knowledgeGapRate", "firstResponseMedianMs", "firstResponseP95Ms")) {
            assertThat(empty.get(key)).as(key).isNull();
        }
        conversation("q", "u", 1L, 11L);
        jdbc.update("UPDATE conversation SET status='need_human',queue_time=now()-interval '11 minutes'");
        count(metrics.realtime(), "queueOverdue", 1);
        try (var ignored = AdminDataScope.use(new AdminDataScope.AccessScope(true, List.of(2L), List.of(22L)))) {
            count(metrics.realtime(), "queueOverdue", 0);
        }
    }

    @Test
    void excelIncludesMetricDefinitionsAndExportsMissingHistoryWithoutExceptions() throws Exception {
        jdbc.update("UPDATE report_metric_tracking SET started_at='2026-09-05'");
        byte[] bytes = new ReportController(jdbc).exportExcel(7, FROM.toString(), TO.toString()).getBody();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheet("核心指标").getRow(4).getCell(1).getStringCellValue()).isEqualTo("暂无数据");
            assertThat(workbook.getSheet("每日趋势").getRow(1).getCell(3).getStringCellValue()).isEqualTo("暂无数据");
        }
    }

    private void conversation(String id, String user, Long tenant, Long account) {
        jdbc.update("INSERT INTO conversation(conversation_id,user_id,tenant_id,kf_account_id,channel) VALUES (?,?,?,?,?)",
                id, user, tenant, account, tenant == null ? "web" : "kf");
    }

    private void user(String id, String at) {
        jdbc.update("INSERT INTO message(conversation_id,role,create_time) VALUES (?,'user',?::timestamp)", id, at);
    }

    private void lead(String user, Long tenant, Long account, String phone, String project, String status) {
        jdbc.update("INSERT INTO appointment(user_id,tenant_id,kf_account_id,phone,project,status,create_time) VALUES (?,?,?,?,?,?,'2026-09-02')",
                user, tenant, account, phone, project, status);
    }

    private void assistant(String type, String knowledge) {
        jdbc.update("INSERT INTO message(conversation_id,role,match_type,decision_trace,create_time) " +
                        "VALUES ('c','assistant',?,?::jsonb,'2026-09-02')", type,
                knowledge == null ? null : "{\"knowledgeStatus\":\"" + knowledge + "\"}");
    }

    private Map<String, Object> overview() { return metrics.overview(FROM, TO); }

    private void count(Map<String, Object> data, String key, long expected) {
        assertThat(((Number) data.get(key)).longValue()).as(key).isEqualTo(expected);
    }

    private String databasePassword() throws Exception {
        String password = System.getenv("POSTGRES_PASSWORD");
        if (password != null && !password.isBlank()) return password;
        for (String line : Files.readAllLines(Path.of(".env"))) {
            if (!line.trim().startsWith("POSTGRES_PASSWORD=")) continue;
            String value = line.trim().substring("POSTGRES_PASSWORD=".length()).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        throw new IllegalStateException("缺少本机测试数据库凭据");
    }
}
