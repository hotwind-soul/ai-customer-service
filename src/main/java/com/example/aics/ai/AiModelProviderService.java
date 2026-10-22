package com.example.aics.ai;

import com.example.aics.config.ConfigService;
import com.example.aics.config.KeyConfigService;
import com.example.aics.config.SecretCryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 管理可热切换的 OpenAI 兼容聊天模型；API Key 只以 AES-GCM 密文落库。 */
@Service
public class AiModelProviderService {

    public static final String ROLE_PRIMARY = "primary";
    public static final String ROLE_FALLBACK = "fallback";
    public static final String ROLE_CANDIDATE = "candidate";
    /** 评测专用角色：仅用于离线质量复核，不参与客户对话主备链路，与 primary/fallback 解耦。 */
    public static final String ROLE_EVAL = "eval";

    private static final RowMapper<ProviderRow> ROW_MAPPER = (rs, rowNum) -> new ProviderRow(
            rs.getLong("id"), rs.getString("provider_code"), rs.getString("display_name"),
            rs.getString("base_url"), rs.getString("model_name"), rs.getString("api_protocol"),
            rs.getString("api_key_ciphertext"), rs.getString("legacy_key_ref"),
            rs.getString("role"), rs.getBoolean("enabled"),
            rs.getTimestamp("create_time"), rs.getTimestamp("update_time"));

    private final JdbcTemplate jdbc;
    private final SecretCryptoService crypto;
    private final KeyConfigService keys;
    private final ConfigService config;

    public AiModelProviderService(JdbcTemplate jdbc, SecretCryptoService crypto,
                                  KeyConfigService keys, ConfigService config) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.keys = keys;
        this.config = config;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProviderRow row : rows()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("code", row.code());
            item.put("displayName", row.displayName());
            item.put("baseUrl", row.baseUrl());
            item.put("modelName", row.modelName());
            item.put("protocol", row.protocol());
            item.put("role", row.role());
            item.put("enabled", row.enabled());
            item.put("apiKeyConfigured", apiKeyConfigured(row));
            item.put("keySource", keySource(row));
            item.put("builtIn", row.legacyKeyRef() != null && !row.legacyKeyRef().isBlank());
            item.put("createTime", row.createTime());
            item.put("updateTime", row.updateTime());
            result.add(item);
        }
        return result;
    }

    /** 读取当前主模型或备用模型；若迁移数据异常缺失，仍用旧配置构造安全兼容项。 */
    public RuntimeModel active(String role) {
        String normalized = activeRole(role);
        List<ProviderRow> found = jdbc.query(
                "SELECT * FROM ai_model_provider WHERE role=? AND enabled ORDER BY id LIMIT 1",
                ROW_MAPPER, normalized);
        ProviderRow row = found.isEmpty() ? legacyRow(normalized) : found.get(0);
        return runtime(row);
    }

    /** 读取指定角色的启用模型；不存在时返回空（不回落 legacy）。评测专用角色应使用此方法，
     *  避免未配置时误用主/备的 legacy 兜底模型。 */
    public Optional<RuntimeModel> activeOptional(String role) {
        String normalized = activeRole(role);
        List<ProviderRow> found = jdbc.query(
                "SELECT * FROM ai_model_provider WHERE role=? AND enabled ORDER BY id LIMIT 1",
                ROW_MAPPER, normalized);
        return found.isEmpty() ? Optional.empty() : Optional.of(runtime(found.get(0)));
    }

    public RuntimeModel byId(long id) {
        return runtime(requireRow(id));
    }

    /**
     * 仅超级管理员"查看模型密钥"用：返回解密后的 API Key（未配置返回空串）。
     * 调用方（Controller）必须校验超级管理员角色并写审计日志。
     */
    public String revealApiKey(long id) {
        RuntimeModel model = byId(id);
        return model.apiKey() == null ? "" : model.apiKey();
    }

    /** 模型表、角色、地址、名称或活动密钥发生变化时，网关会在下一次调用前热重建。 */
    public String configurationSignature() {
        StringBuilder signature = new StringBuilder();
        for (ProviderRow row : rows()) {
            signature.append(row.id()).append('\u001f').append(row.role()).append('\u001f')
                    .append(row.enabled()).append('\u001f').append(row.baseUrl()).append('\u001f')
                    .append(row.modelName()).append('\u001f')
                    .append(row.updateTime() == null ? 0 : row.updateTime().getTime()).append('\u001f');
            if (row.enabled() && (ROLE_PRIMARY.equals(row.role()) || ROLE_FALLBACK.equals(row.role())
                    || ROLE_EVAL.equals(row.role()))) {
                // 仅用于进程内变化检测，不对外返回；避免在内存签名中保存原始密钥。
                signature.append(resolveApiKey(row).hashCode());
            }
            signature.append('\u001e');
        }
        return signature.toString();
    }

    @Transactional
    public long create(String displayName, String baseUrl, String modelName, String apiKey,
                       boolean enabled, String requestedRole) {
        String name = required(displayName, "显示名称", 100);
        String url = normalizeBaseUrl(baseUrl);
        String model = required(modelName, "模型标识", 160);
        String secret = required(apiKey, "API Key", 8192);
        String role = normalizeRole(requestedRole, true);
        if (!enabled && !ROLE_CANDIDATE.equals(role) && !ROLE_EVAL.equals(role)) {
            throw new IllegalArgumentException("主模型或备用模型必须处于启用状态");
        }
        String code = "custom-" + UUID.randomUUID().toString().replace("-", "");
        Long id = jdbc.queryForObject(
                "INSERT INTO ai_model_provider(provider_code,display_name,base_url,model_name," +
                        "api_protocol,api_key_ciphertext,legacy_key_ref,role,enabled,create_time,update_time) " +
                        "VALUES (?,?,?,?,'openai-compatible',?,NULL,'candidate',?,now(),now()) RETURNING id",
                Long.class, code, name, url, model, crypto.encrypt(secret), enabled);
        if (id == null) throw new IllegalStateException("模型创建失败");
        if (!ROLE_CANDIDATE.equals(role)) assignRole(id, role);
        return id;
    }

    public void update(long id, String displayName, String baseUrl, String modelName, String apiKey) {
        requireRow(id);
        String name = required(displayName, "显示名称", 100);
        String url = normalizeBaseUrl(baseUrl);
        String model = required(modelName, "模型标识", 160);
        if (apiKey == null || apiKey.isBlank()) {
            jdbc.update("UPDATE ai_model_provider SET display_name=?,base_url=?,model_name=?,update_time=now() WHERE id=?",
                    name, url, model, id);
        } else {
            String secret = required(apiKey, "API Key", 8192);
            jdbc.update("UPDATE ai_model_provider SET display_name=?,base_url=?,model_name=?," +
                            "api_key_ciphertext=?,update_time=now() WHERE id=?",
                    name, url, model, crypto.encrypt(secret), id);
        }
    }

    /**
     * 设置主/备用角色。若点选的是另一角色当前使用的模型，则原主备会自动交换，
     * 从而始终保留完整的主备链路。
     */
    @Transactional
    public void assignRole(long id, String requestedRole) {
        String role = normalizeRole(requestedRole, true);
        ProviderRow target = requireRow(id);
        if (role.equals(target.role())) return;

        // 降级为普通候选或设为评测专用：直接更新，不参与主备互换，也不强制要求已启用/已配 Key
        if (ROLE_CANDIDATE.equals(role) || ROLE_EVAL.equals(role)) {
            jdbc.update("UPDATE ai_model_provider SET role=?,update_time=now() WHERE id=?", role, target.id());
            return;
        }

        if (!target.enabled()) throw new IllegalArgumentException("请先启用该模型，再设置主模型或备用模型");
        if (resolveApiKey(target).isBlank()) throw new IllegalArgumentException("该模型尚未配置 API Key");

        String otherRole = ROLE_PRIMARY.equals(role) ? ROLE_FALLBACK : ROLE_PRIMARY;
        Optional<ProviderRow> currentForRole = findRole(role);
        if (otherRole.equals(target.role())) {
            // 主备互换时先临时降为候选，避免触发部分唯一索引。
            currentForRole.ifPresent(current -> jdbc.update(
                    "UPDATE ai_model_provider SET role='candidate',update_time=now() WHERE id=?", current.id()));
            jdbc.update("UPDATE ai_model_provider SET role='candidate',update_time=now() WHERE id=?", target.id());
            jdbc.update("UPDATE ai_model_provider SET role=?,update_time=now() WHERE id=?", role, target.id());
            currentForRole.ifPresent(current -> jdbc.update(
                    "UPDATE ai_model_provider SET role=?,update_time=now() WHERE id=?", otherRole, current.id()));
            return;
        }

        currentForRole.ifPresent(current -> jdbc.update(
                "UPDATE ai_model_provider SET role='candidate',update_time=now() WHERE id=?", current.id()));
        jdbc.update("UPDATE ai_model_provider SET role=?,update_time=now() WHERE id=?", role, target.id());
    }

    public void setEnabled(long id, boolean enabled) {
        ProviderRow row = requireRow(id);
        if (!enabled && (ROLE_PRIMARY.equals(row.role()) || ROLE_FALLBACK.equals(row.role()))) {
            throw new IllegalArgumentException("请先把其他模型设为" +
                    (ROLE_PRIMARY.equals(row.role()) ? "主模型" : "备用模型") + "，再停用当前模型");
        }
        jdbc.update("UPDATE ai_model_provider SET enabled=?,update_time=now() WHERE id=?", enabled, id);
    }

    public void delete(long id) {
        ProviderRow row = requireRow(id);
        if (ROLE_PRIMARY.equals(row.role()) || ROLE_FALLBACK.equals(row.role())) {
            throw new IllegalArgumentException("正在使用的主模型或备用模型不能删除，请先切换到其他模型");
        }
        jdbc.update("DELETE FROM ai_model_provider WHERE id=?", id);
    }

    private RuntimeModel runtime(ProviderRow row) {
        return new RuntimeModel(row.id(), row.code(), row.displayName(), row.baseUrl(),
                row.modelName(), resolveApiKey(row), row.role(), row.enabled());
    }

    private String resolveApiKey(ProviderRow row) {
        if (row.apiKeyCiphertext() != null && !row.apiKeyCiphertext().isBlank()) {
            return crypto.decrypt(row.apiKeyCiphertext());
        }
        return switch (row.legacyKeyRef() == null ? "" : row.legacyKeyRef()) {
            case "ai.deepseek_key" -> keys.get("ai.deepseek_key", "DEEPSEEK_API_KEY");
            case "ai.bigmodel_key" -> keys.get("ai.bigmodel_key", "BIGMODEL_API_KEY");
            default -> "";
        };
    }

    private boolean apiKeyConfigured(ProviderRow row) {
        if (row.apiKeyCiphertext() != null && !row.apiKeyCiphertext().isBlank()) return true;
        return switch (row.legacyKeyRef() == null ? "" : row.legacyKeyRef()) {
            case "ai.deepseek_key" -> keys.has("ai.deepseek_key", "DEEPSEEK_API_KEY");
            case "ai.bigmodel_key" -> keys.has("ai.bigmodel_key", "BIGMODEL_API_KEY");
            default -> false;
        };
    }

    private String keySource(ProviderRow row) {
        if (row.apiKeyCiphertext() != null && !row.apiKeyCiphertext().isBlank()) return "模型配置";
        return apiKeyConfigured(row) && row.legacyKeyRef() != null ? "历史接口设置" : "";
    }

    private List<ProviderRow> rows() {
        return jdbc.query("SELECT * FROM ai_model_provider " +
                "ORDER BY CASE role WHEN 'primary' THEN 0 WHEN 'fallback' THEN 1 ELSE 2 END, id", ROW_MAPPER);
    }

    private ProviderRow requireRow(long id) {
        List<ProviderRow> found = jdbc.query("SELECT * FROM ai_model_provider WHERE id=?", ROW_MAPPER, id);
        if (found.isEmpty()) throw new IllegalArgumentException("模型配置不存在");
        return found.get(0);
    }

    private Optional<ProviderRow> findRole(String role) {
        List<ProviderRow> found = jdbc.query(
                "SELECT * FROM ai_model_provider WHERE role=? AND enabled ORDER BY id LIMIT 1",
                ROW_MAPPER, role);
        return found.stream().findFirst();
    }

    private ProviderRow legacyRow(String role) {
        boolean primary = ROLE_PRIMARY.equals(role);
        String code = primary ? "deepseek-legacy" : "bigmodel-legacy";
        String name = primary ? "DeepSeek" : "智谱开放平台";
        String baseUrl = config.get(primary ? "model.primary.baseUrl" : "model.fallback.baseUrl",
                primary ? "https://api.deepseek.com/v1" : "https://open.bigmodel.cn/api/paas/v4");
        String modelName = config.get(primary ? "model.primary.name" : "model.fallback.name",
                primary ? "deepseek-v4-flash" : "glm-4-flash");
        String legacyRef = primary ? "ai.deepseek_key" : "ai.bigmodel_key";
        return new ProviderRow(0, code, name, baseUrl, modelName, "openai-compatible",
                null, legacyRef, role, true, null, null);
    }

    private static String normalizeRole(String role, boolean allowCandidate) {
        String value = role == null ? ROLE_CANDIDATE : role.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) value = ROLE_CANDIDATE;
        if (ROLE_PRIMARY.equals(value) || ROLE_FALLBACK.equals(value) || ROLE_EVAL.equals(value)
                || allowCandidate && ROLE_CANDIDATE.equals(value)) return value;
        throw new IllegalArgumentException("模型角色只能是主模型、备用模型、评测专用模型或普通候选");
    }

    private static String activeRole(String role) {
        return normalizeRole(role, false);
    }

    static String normalizeBaseUrl(String raw) {
        String value = required(raw, "API Base URL", 500);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("API Base URL 必须是完整的 http/https 地址，且不能包含账号、查询参数或锚点");
            }
            boolean local = "localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
            if ("http".equals(scheme) && !local) {
                throw new IllegalArgumentException("外部模型接口必须使用 HTTPS；本机调试地址可以使用 HTTP");
            }
            // W28-SSRF 加固：非本机调试地址一律拒绝解析到内网/链路本地/任意地址（含域名解析到内网的情况）。
            if (!local && isPrivateNetworkHost(uri.getHost())) {
                throw new IllegalArgumentException("API Base URL 不能指向内网/私有地址（含解析到内网的域名）");
            }
            if (uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
                throw new IllegalArgumentException("请填写 API Base URL，不要包含 /chat/completions");
            }
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("API Base URL 格式不正确");
        }
    }

    /** 判断主机是否解析到内网/保留地址；DNS 解析失败不在此误伤（后续真实调用会报错暴露）。 */
    private static boolean isPrivateNetworkHost(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 解析失败（未知主机/超时）交给连接层报错，不在校验阶段拒绝合法输入
        }
        return false;
    }

    private static String required(String raw, String label, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        if (value.length() > maxLength) throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        return value;
    }

    private record ProviderRow(long id, String code, String displayName, String baseUrl,
                               String modelName, String protocol, String apiKeyCiphertext,
                               String legacyKeyRef, String role, boolean enabled,
                               java.sql.Timestamp createTime, java.sql.Timestamp updateTime) {}

    public record RuntimeModel(long id, String code, String displayName, String baseUrl,
                               String modelName, String apiKey, String role, boolean enabled) {}
}
