package com.example.aics.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/** 发布门禁读取的启用回归用例快照。 */
@Repository
public class PromptRegressionCaseCatalog {
    private final JdbcTemplate jdbc;

    public PromptRegressionCaseCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CaseDefinition(
            long id,
            String name,
            String message,
            String history,
            String expectedReplyType,
            String expectedAnswerPoints,
            String forbiddenAnswerPoints,
            String expectedAuthorityDomain,
            String expectedAuthoritySource,
            Boolean expectedModelInvoked,
            LocalDateTime updateTime
    ) {}

    public record Snapshot(String hash, List<CaseDefinition> cases) {
        public Snapshot {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public Snapshot snapshot() {
        List<CaseDefinition> cases = jdbc.query(
                "SELECT id,name,message,history,expected_reply_type,expected_answer_points," +
                        "forbidden_answer_points,expected_authority_domain,expected_authority_source," +
                        "expected_model_invoked,update_time FROM ai_regression_case " +
                        "WHERE enabled=true ORDER BY id",
                (rs, rowNum) -> new CaseDefinition(
                        rs.getLong("id"), rs.getString("name"), rs.getString("message"),
                        rs.getString("history"), rs.getString("expected_reply_type"),
                        rs.getString("expected_answer_points"), rs.getString("forbidden_answer_points"),
                        rs.getString("expected_authority_domain"), rs.getString("expected_authority_source"),
                        nullableBoolean(rs.getObject("expected_model_invoked")),
                        rs.getTimestamp("update_time") == null ? null
                                : rs.getTimestamp("update_time").toLocalDateTime()));
        StringBuilder fingerprint = new StringBuilder();
        for (CaseDefinition item : cases) {
            append(fingerprint, item.id());
            append(fingerprint, item.name());
            append(fingerprint, item.message());
            append(fingerprint, item.history());
            append(fingerprint, item.expectedReplyType());
            append(fingerprint, item.expectedAnswerPoints());
            append(fingerprint, item.forbiddenAnswerPoints());
            append(fingerprint, item.expectedAuthorityDomain());
            append(fingerprint, item.expectedAuthoritySource());
            append(fingerprint, item.expectedModelInvoked());
            append(fingerprint, item.updateTime());
        }
        return new Snapshot(hash(fingerprint.toString()), cases);
    }

    private static Boolean nullableBoolean(Object value) {
        return value == null ? null : (Boolean) value;
    }

    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算回归用例指纹", e);
        }
    }
}
