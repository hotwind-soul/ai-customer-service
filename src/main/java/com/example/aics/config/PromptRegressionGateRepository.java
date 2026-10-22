package com.example.aics.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prompt 回归门禁任务和可发布凭证的持久化。 */
@Repository
public class PromptRegressionGateRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> FAILURE_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;

    public PromptRegressionGateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasActive(String actor) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_prompt_regression_gate_run " +
                        "WHERE requested_by=? AND status IN ('queued','running')", Integer.class, actor);
        return count != null && count > 0;
    }

    public long create(String actor, long baseReleaseId, String draftHash,
                       String caseSetHash, String environmentHash, int requestedCount) {
        Long id = jdbc.queryForObject(
                "INSERT INTO ai_prompt_regression_gate_run(requested_by,base_release_id,draft_hash," +
                        "case_set_hash,environment_hash,requested_count) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, actor, baseReleaseId, draftHash, caseSetHash, environmentHash, requestedCount);
        if (id == null) throw new IllegalStateException("创建 Prompt 回归门禁任务失败");
        return id;
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE ai_prompt_regression_gate_run SET status='running',start_time=now()," +
                "error_message=NULL WHERE id=?", id);
    }

    public void progress(long id, int processed, int passed, List<Map<String, Object>> failures,
                         Long currentCaseId) {
        jdbc.update("UPDATE ai_prompt_regression_gate_run SET processed_count=?,passed_count=?," +
                        "failed_count=?,failures_json=CAST(? AS jsonb),current_case_id=? WHERE id=?",
                processed, passed, failures.size(), json(failures), currentCaseId, id);
    }

    public void finish(long id, boolean passed, int passedCount, List<Map<String, Object>> failures) {
        jdbc.update("UPDATE ai_prompt_regression_gate_run SET status=?,processed_count=requested_count," +
                        "passed_count=?,failed_count=?,failures_json=CAST(? AS jsonb),current_case_id=NULL," +
                        "finish_time=now() WHERE id=?",
                passed ? "passed" : "failed", passedCount, failures.size(), json(failures), id);
    }

    public void fail(long id, String message) {
        jdbc.update("UPDATE ai_prompt_regression_gate_run SET status='failed',current_case_id=NULL," +
                        "error_message=?,finish_time=now() WHERE id=?",
                truncate(message, 1000), id);
    }

    public void failInterrupted() {
        jdbc.update("UPDATE ai_prompt_regression_gate_run SET status='failed'," +
                "error_message='应用重启导致发布检查中断，请重新运行',current_case_id=NULL," +
                "finish_time=now() WHERE status IN ('queued','running')");
    }

    /** 只认可同一草稿/用例集的最新运行；新的失败或排队任务也会使旧通过记录失效。 */
    public boolean hasPassingProof(String actor, long baseReleaseId, String draftHash, String caseSetHash,
                                    String environmentHash) {
        return Boolean.TRUE.equals(latest(actor, baseReleaseId, draftHash, caseSetHash, environmentHash)
                .get("proofValid"));
    }

    public Map<String, Object> status(long id, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql()
                + " WHERE id=? AND requested_by=?", id, actor);
        return rows.isEmpty() ? Map.of("found", false) : view(rows.get(0));
    }

    public Map<String, Object> latest(String actor, long baseReleaseId, String draftHash, String caseSetHash,
                                      String environmentHash) {
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql()
                        + " WHERE requested_by=? AND base_release_id=? AND draft_hash=? AND case_set_hash=? " +
                        "ORDER BY id DESC LIMIT 1",
                actor, baseReleaseId, draftHash, caseSetHash);
        if (rows.isEmpty()) return Map.of("found", false);
        Map<String, Object> result = view(rows.get(0));
        result.put("proofValid", Boolean.TRUE.equals(result.get("proofValid"))
                && environmentHash != null && !environmentHash.isBlank()
                && environmentHash.equals(result.get("environmentHash")));
        return result;
    }

    private String selectSql() {
        return "SELECT id,status,base_release_id AS \"baseReleaseId\",draft_hash AS \"draftHash\"," +
                "environment_hash AS \"environmentHash\"," +
                "case_set_hash AS \"caseSetHash\",requested_count AS \"requestedCount\"," +
                "processed_count AS \"processedCount\",passed_count AS \"passedCount\"," +
                "failed_count AS \"failedCount\",current_case_id AS \"currentCaseId\"," +
                "failures_json::text AS \"failuresJson\",error_message AS \"errorMessage\"," +
                "(status='passed' AND requested_count>0 AND processed_count=requested_count " +
                "AND passed_count=requested_count AND failed_count=0 AND error_message IS NULL " +
                "AND finish_time >= now() - interval '24 hours' AND NOT EXISTS (" +
                "SELECT 1 FROM ai_prompt_regression_gate_run newer WHERE newer.requested_by=r.requested_by " +
                "AND newer.base_release_id=r.base_release_id AND newer.draft_hash=r.draft_hash " +
                "AND newer.case_set_hash=r.case_set_hash AND newer.id>r.id)) AS \"proofValid\"," +
                "create_time AS \"createTime\",start_time AS \"startTime\",finish_time AS \"finishTime\" " +
                "FROM ai_prompt_regression_gate_run r";
    }

    private Map<String, Object> view(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("found", true);
        result.put("failures", parseFailures(String.valueOf(result.remove("failuresJson"))));
        String status = String.valueOf(result.get("status"));
        result.put("active", "queued".equals(status) || "running".equals(status));
        result.put("proofValid", Boolean.TRUE.equals(source.get("proofValid")));
        return result;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Prompt 回归结果失败", e);
        }
    }

    private static List<Map<String, Object>> parseFailures(String value) {
        try {
            return value == null || value.isBlank() || "null".equals(value)
                    ? List.of() : JSON.readValue(value, FAILURE_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
