package com.example.aics.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcPromptReleaseStore implements PromptReleaseStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcPromptReleaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long lockCurrentReleaseId() {
        return jdbc.queryForObject(
                "SELECT current_release_id FROM ai_prompt_release_state WHERE singleton_id=1 FOR UPDATE",
                (rs, rowNum) -> {
                    Object value = rs.getObject("current_release_id");
                    return value == null ? null : ((Number) value).longValue();
                });
    }

    @Override
    public void setCurrentReleaseId(long releaseId) {
        jdbc.update("UPDATE ai_prompt_release_state SET current_release_id=?,update_time=now() " +
                "WHERE singleton_id=1", releaseId);
    }

    @Override
    public Release insertRelease(String action, Long sourceReleaseId, Map<String, String> snapshot,
                                 List<String> changedKeys, String actor) {
        Long id = jdbc.queryForObject(
                "INSERT INTO ai_prompt_release(action,source_release_id,snapshot,changed_keys,created_by) " +
                        "VALUES (?,?,CAST(? AS jsonb),CAST(? AS jsonb),?) RETURNING id",
                Long.class, action, sourceReleaseId, json(snapshot), json(changedKeys), actor);
        if (id == null) throw new IllegalStateException("创建 Prompt 发布记录失败");
        return findRelease(id);
    }

    @Override
    public Release findRelease(long id) {
        List<Release> rows = jdbc.query(
                "SELECT id,action,source_release_id,snapshot::text AS snapshot_json," +
                        "changed_keys::text AS changed_json,created_by,create_time " +
                        "FROM ai_prompt_release WHERE id=?",
                (rs, rowNum) -> new Release(
                        rs.getLong("id"),
                        rs.getString("action"),
                        nullableLong(rs.getObject("source_release_id")),
                        stringMap(rs.getString("snapshot_json")),
                        stringList(rs.getString("changed_json")),
                        rs.getString("created_by"),
                        localDateTime(rs.getTimestamp("create_time"))), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Release findPreviousRelease(long id) {
        List<Release> rows = jdbc.query(
                "SELECT id,action,source_release_id,snapshot::text AS snapshot_json," +
                        "changed_keys::text AS changed_json,created_by,create_time " +
                        "FROM ai_prompt_release WHERE id<? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new Release(
                        rs.getLong("id"), rs.getString("action"),
                        nullableLong(rs.getObject("source_release_id")),
                        stringMap(rs.getString("snapshot_json")),
                        stringList(rs.getString("changed_json")),
                        rs.getString("created_by"), localDateTime(rs.getTimestamp("create_time"))), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Release> listReleases(int limit) {
        return jdbc.query(
                "SELECT id,action,source_release_id,snapshot::text AS snapshot_json," +
                        "changed_keys::text AS changed_json,created_by,create_time " +
                        "FROM ai_prompt_release ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new Release(
                        rs.getLong("id"), rs.getString("action"),
                        nullableLong(rs.getObject("source_release_id")),
                        stringMap(rs.getString("snapshot_json")),
                        stringList(rs.getString("changed_json")),
                        rs.getString("created_by"), localDateTime(rs.getTimestamp("create_time"))), limit);
    }

    @Override
    public Draft findDraft(String owner) {
        List<Draft> rows = jdbc.query(
                "SELECT owner,base_release_id,snapshot::text AS snapshot_json,update_time " +
                        "FROM ai_prompt_draft WHERE owner=?",
                (rs, rowNum) -> new Draft(
                        rs.getString("owner"), rs.getLong("base_release_id"),
                        stringMap(rs.getString("snapshot_json")),
                        localDateTime(rs.getTimestamp("update_time"))), owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void saveDraft(String owner, long baseReleaseId, Map<String, String> snapshot) {
        jdbc.update("INSERT INTO ai_prompt_draft(owner,base_release_id,snapshot,update_time) " +
                        "VALUES (?,?,CAST(? AS jsonb),now()) ON CONFLICT (owner) DO UPDATE SET " +
                        "base_release_id=EXCLUDED.base_release_id,snapshot=EXCLUDED.snapshot,update_time=now()",
                owner, baseReleaseId, json(snapshot));
    }

    @Override
    public void deleteDraft(String owner) {
        jdbc.update("DELETE FROM ai_prompt_draft WHERE owner=?", owner);
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Prompt 快照失败", e);
        }
    }

    private static Map<String, String> stringMap(String value) {
        try {
            if (value == null || value.isBlank()) return Map.of();
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("读取 Prompt 快照失败", e);
        }
    }

    private static List<String> stringList(String value) {
        try {
            if (value == null || value.isBlank()) return List.of();
            return JSON.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("读取 Prompt 变更列表失败", e);
        }
    }
}
