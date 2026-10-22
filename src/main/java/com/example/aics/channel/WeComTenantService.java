package com.example.aics.channel;

import com.example.aics.config.SecretCryptoService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 多企业微信客服租户和客服号目录。敏感值只以 AES-GCM 密文落库，接口永不回显明文。 */
@Service
public class WeComTenantService {

    public record TenantCredentials(long id, String code, String name, String corpId,
                                    String secret, String callbackToken, String aesKey,
                                    boolean enabled) {
        public boolean apiConfigured() {
            return !corpId.isBlank() && !secret.isBlank();
        }

        public boolean cryptoConfigured() {
            return !corpId.isBlank() && !callbackToken.isBlank() && aesKey.length() == 43;
        }
    }

    public record KfAccount(long id, long tenantId, String openKfId, String name,
                            String businessScope, String welcomeMessage, boolean enabled) {
    }

    public record AccountSyncSummary(int remoteCount, int inserted, int renamed,
                                     int unchanged, int localOnly) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCryptoService crypto;

    public WeComTenantService(JdbcTemplate jdbc, SecretCryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public List<Map<String, Object>> listTenants() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id, tenant_code, name, corp_id, secret_ciphertext, " +
                        "callback_token_ciphertext, aes_key_ciphertext, enabled, create_time, update_time " +
                        "FROM wecom_tenant ORDER BY id");
        List<Map<String, Object>> result = new ArrayList<>(tenants.size());
        for (Map<String, Object> row : tenants) {
            long tenantId = number(row.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tenantId);
            item.put("code", text(row.get("tenant_code")));
            item.put("name", text(row.get("name")));
            item.put("corpId", text(row.get("corp_id")));
            item.put("secretConfigured", present(row.get("secret_ciphertext")));
            item.put("callbackTokenConfigured", present(row.get("callback_token_ciphertext")));
            item.put("aesKeyConfigured", present(row.get("aes_key_ciphertext")));
            item.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
            item.put("createTime", row.get("create_time"));
            item.put("updateTime", row.get("update_time"));
            item.put("accounts", listAccounts(tenantId));
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> listAccounts(long tenantId) {
        return jdbc.queryForList(
                "SELECT id, tenant_id, open_kfid, name, business_scope, welcome_message, " +
                        "enabled, create_time, update_time FROM wecom_kf_account " +
                        "WHERE tenant_id = ? ORDER BY id", tenantId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("tenantId", row.get("tenant_id"));
            item.put("openKfid", row.get("open_kfid"));
            item.put("name", row.get("name"));
            item.put("businessScope", row.get("business_scope"));
            item.put("welcomeMessage", row.get("welcome_message"));
            item.put("enabled", row.get("enabled"));
            item.put("createTime", row.get("create_time"));
            item.put("updateTime", row.get("update_time"));
            return item;
        }).toList();
    }

    @Transactional
    public long createTenant(String code, String name, String corpId, String secret,
                             String callbackToken, String aesKey, boolean enabled) {
        code = normalizeCode(code);
        name = requireText(name, "企业名称", 100);
        corpId = requireText(corpId, "企业 ID", 128);
        secret = requireText(secret, "接口调用应用 Secret", 500);
        callbackToken = requireText(callbackToken, "回调 Token", 500);
        aesKey = validateAesKey(aesKey);
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO wecom_tenant(tenant_code, name, corp_id, secret_ciphertext, " +
                            "callback_token_ciphertext, aes_key_ciphertext, enabled) " +
                            "VALUES (?,?,?,?,?,?,?) RETURNING id",
                    Long.class, code, name, corpId, crypto.encrypt(secret),
                    crypto.encrypt(callbackToken), crypto.encrypt(aesKey), enabled);
            return id == null ? 0 : id;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("企业编码已存在，请更换", e);
        }
    }

    @Transactional
    public void updateTenant(long id, String code, String name, String corpId,
                             String secret, String callbackToken, String aesKey,
                             boolean enabled) {
        TenantCredentials current = requireTenantById(id, false);
        code = normalizeCode(code);
        // 企业编码冻结：企微后台回调地址按此编码配置，改动会让回调 404 且难以排查。
        if (!code.equals(current.code())) {
            throw new IllegalArgumentException("企业编码创建后不可修改：企微后台的回调地址已按此编码配置，改动会导致回调 404");
        }
        name = requireText(name, "企业名称", 100);
        corpId = requireText(corpId, "企业 ID", 128);
        String secretCipher = secret == null || secret.isBlank()
                ? encryptedColumn(id, "secret_ciphertext") : crypto.encrypt(secret.trim());
        String tokenCipher = callbackToken == null || callbackToken.isBlank()
                ? encryptedColumn(id, "callback_token_ciphertext") : crypto.encrypt(callbackToken.trim());
        String aesCipher = aesKey == null || aesKey.isBlank()
                ? encryptedColumn(id, "aes_key_ciphertext") : crypto.encrypt(validateAesKey(aesKey));
        if (current.id() <= 0) throw new IllegalArgumentException("企业不存在");
        try {
            int updated = jdbc.update(
                    "UPDATE wecom_tenant SET tenant_code=?, name=?, corp_id=?, secret_ciphertext=?, " +
                            "callback_token_ciphertext=?, aes_key_ciphertext=?, enabled=?, update_time=now() " +
                            "WHERE id=?",
                    code, name, corpId, secretCipher, tokenCipher, aesCipher, enabled, id);
            if (updated == 0) throw new IllegalArgumentException("企业不存在");
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("企业编码已存在，请更换", e);
        }
    }

    /**
     * 删除企业。
     *
     * 外键语义不一致：knowledge_document / knowledge_chunk / image_asset / store / business_fact 声明的是
     * ON DELETE CASCADE（会被物理删除），而 conversation / appointment / leave_message / wake_task 是
     * SET NULL（tenant_id 被置空后，ScopedDataAccess 会把这些数据当作"全局共享"对所有企业可见）。
     * 直接删会同时造成不可恢复的数据丢失和跨租户可见的孤儿数据，所以这里改为：只要该企业下还有业务数据
     * 就拒绝删除，引导改用「停用」；确需清库时先人工清理数据再删。
     */
    @Transactional
    public void deleteTenant(long id) {
        // 表名均为代码内字面量常量，不含任何外部输入。
        String[][] refs = {
                {"wecom_kf_account", "客服号"},
                {"conversation", "会话"},
                {"store", "门店"},
                {"knowledge_document", "知识文档"},
                {"knowledge_chunk", "知识切片"},
                {"image_asset", "图片素材"},
                {"business_fact", "业务事实"},
                {"appointment", "预约"},
                {"leave_message", "留言"},
                {"wake_task", "唤醒任务"},
        };
        java.util.Map<String, Integer> blocking = new java.util.LinkedHashMap<>();
        for (String[] ref : refs) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + ref[0] + " WHERE tenant_id = ?", Integer.class, id);
            if (count != null && count > 0) blocking.put(ref[1], count);
        }
        if (!blocking.isEmpty()) {
            String detail = blocking.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue() + " 条")
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new IllegalArgumentException(
                    "该企业下仍有数据（" + detail + "），删除会物理清除其中一部分、"
                            + "并把其余记录变成全局共享。请改用「停用」。");
        }
        int deleted = jdbc.update("DELETE FROM wecom_tenant WHERE id = ?", id);
        if (deleted == 0) throw new IllegalArgumentException("企业不存在");
    }

    @Transactional
    public long createAccount(long tenantId, String openKfId, String name,
                              String businessScope, String welcomeMessage, boolean enabled) {
        requireTenantById(tenantId, false);
        openKfId = requireIdentifier(openKfId, "open_kfid");
        name = requireText(name, "客服号名称", 100);
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO wecom_kf_account(tenant_id, open_kfid, name, business_scope, " +
                            "welcome_message, enabled) VALUES (?,?,?,?,?,?) RETURNING id",
                    Long.class, tenantId, openKfId, name, trim(businessScope, 500),
                    trim(welcomeMessage, 4000), enabled);
            return id == null ? 0 : id;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该企业下已经存在相同的 open_kfid", e);
        }
    }

    @Transactional
    public void updateAccount(long tenantId, long accountId, String openKfId, String name,
                              String businessScope, String welcomeMessage, boolean enabled) {
        openKfId = requireIdentifier(openKfId, "open_kfid");
        name = requireText(name, "客服号名称", 100);
        try {
            int updated = jdbc.update(
                    "UPDATE wecom_kf_account SET open_kfid=?, name=?, business_scope=?, " +
                            "welcome_message=?, enabled=?, update_time=now() WHERE id=? AND tenant_id=?",
                    openKfId, name, trim(businessScope, 500), trim(welcomeMessage, 4000),
                    enabled, accountId, tenantId);
            if (updated == 0) throw new IllegalArgumentException("客服号不存在");
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该企业下已经存在相同的 open_kfid", e);
        }
    }

    @Transactional
    public void deleteAccount(long tenantId, long accountId) {
        int deleted = jdbc.update(
                "DELETE FROM wecom_kf_account WHERE id=? AND tenant_id=?", accountId, tenantId);
        if (deleted == 0) throw new IllegalArgumentException("客服号不存在");
    }

    /**
     * 把企微账号目录安全合并到本地：新账号默认停用，已有账号只同步官方名称。
     * 本地业务范围、欢迎语和启用状态均保留；企微侧已不存在的账号也不自动删除。
     */
    @Transactional
    public AccountSyncSummary synchronizeAccounts(
            long tenantId, List<WeComKfApiClient.KfAccountProfile> remoteAccounts) {
        requireTenantById(tenantId, false);
        List<Map<String, Object>> existingRows = jdbc.queryForList(
                "SELECT id, open_kfid, name FROM wecom_kf_account WHERE tenant_id=?", tenantId);
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (Map<String, Object> row : existingRows) {
            existing.put(text(row.get("open_kfid")), row);
        }

        Set<String> remoteIds = new HashSet<>();
        int inserted = 0;
        int renamed = 0;
        int unchanged = 0;
        for (WeComKfApiClient.KfAccountProfile remote :
                remoteAccounts == null ? List.<WeComKfApiClient.KfAccountProfile>of() : remoteAccounts) {
            String openKfId = requireIdentifier(remote.openKfId(), "open_kfid");
            String name = requireText(remote.name(), "客服号名称", 100);
            if (!remoteIds.add(openKfId)) continue;
            Map<String, Object> local = existing.get(openKfId);
            if (local == null) {
                inserted += jdbc.update(
                        "INSERT INTO wecom_kf_account(tenant_id, open_kfid, name, business_scope, " +
                                "welcome_message, enabled) VALUES (?,?,?,'','',FALSE) " +
                                "ON CONFLICT (tenant_id, open_kfid) DO NOTHING",
                        tenantId, openKfId, name);
            } else if (!name.equals(text(local.get("name")))) {
                renamed += jdbc.update(
                        "UPDATE wecom_kf_account SET name=?, update_time=now() " +
                                "WHERE id=? AND tenant_id=?",
                        name, number(local.get("id")), tenantId);
            } else {
                unchanged++;
            }
        }
        int localOnly = (int) existing.keySet().stream()
                .filter(openKfId -> !remoteIds.contains(openKfId)).count();
        return new AccountSyncSummary(remoteIds.size(), inserted, renamed, unchanged, localOnly);
    }

    public TenantCredentials requireEnabledTenant(String code) {
        String normalized = normalizeCode(code);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wecom_tenant WHERE tenant_code=? AND enabled=TRUE", normalized);
        if (rows.isEmpty()) throw new IllegalArgumentException("企业不存在或已停用");
        return credentials(rows.get(0));
    }

    public TenantCredentials requireTenantById(long id, boolean enabledOnly) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wecom_tenant WHERE id=?" + (enabledOnly ? " AND enabled=TRUE" : ""), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("企业不存在或已停用");
        return credentials(rows.get(0));
    }

    public KfAccount requireEnabledAccount(long tenantId, String openKfId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, open_kfid, name, business_scope, welcome_message, enabled " +
                        "FROM wecom_kf_account WHERE tenant_id=? AND open_kfid=? AND enabled=TRUE",
                tenantId, openKfId == null ? "" : openKfId.trim());
        if (rows.isEmpty()) throw new IllegalArgumentException("该企业的微信客服号不存在或已停用");
        return account(rows.get(0));
    }

    private TenantCredentials credentials(Map<String, Object> row) {
        return new TenantCredentials(number(row.get("id")), text(row.get("tenant_code")),
                text(row.get("name")), text(row.get("corp_id")),
                crypto.decrypt(text(row.get("secret_ciphertext"))),
                crypto.decrypt(text(row.get("callback_token_ciphertext"))),
                crypto.decrypt(text(row.get("aes_key_ciphertext"))),
                Boolean.TRUE.equals(row.get("enabled")));
    }

    private KfAccount account(Map<String, Object> row) {
        return new KfAccount(number(row.get("id")), number(row.get("tenant_id")),
                text(row.get("open_kfid")), text(row.get("name")),
                text(row.get("business_scope")), text(row.get("welcome_message")),
                Boolean.TRUE.equals(row.get("enabled")));
    }

    private String encryptedColumn(long id, String column) {
        if (!List.of("secret_ciphertext", "callback_token_ciphertext", "aes_key_ciphertext")
                .contains(column)) throw new IllegalArgumentException("无效密钥列");
        List<String> values = jdbc.queryForList(
                "SELECT " + column + " FROM wecom_tenant WHERE id=?", String.class, id);
        if (values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            throw new IllegalArgumentException("当前密钥未配置，请填写后保存");
        }
        return values.get(0);
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toLowerCase();
        if (!value.matches("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$")) {
            throw new IllegalArgumentException("企业编码须为 3-50 位小写字母、数字或连字符，且首尾不能是连字符");
        }
        return value;
    }

    private static String requireIdentifier(String value, String label) {
        String result = requireText(value, label, 128);
        if (!result.matches("^[A-Za-z0-9_-]{3,128}$")) {
            throw new IllegalArgumentException(label + " 格式不正确");
        }
        return result;
    }

    private static String validateAesKey(String value) {
        String result = requireText(value, "EncodingAESKey", 43);
        if (result.length() != 43 || !result.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException("EncodingAESKey 必须是 43 位字符");
        }
        return result;
    }

    private static String requireText(String value, String label, int max) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        if (result.length() > max) throw new IllegalArgumentException(label + "不能超过 " + max + " 个字符");
        return result;
    }

    private static String trim(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw new IllegalArgumentException("内容不能超过 " + max + " 个字符");
        return result;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean present(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }
}
