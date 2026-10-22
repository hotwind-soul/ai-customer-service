package com.example.aics.config;

import com.example.aics.ai.AiModelProviderService;
import com.example.aics.chat.HarnessAssertions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 仅在门禁开始、结束及校验凭证时计算，不进入客户聊天的请求路径。 */
@Service
public class HarnessEnvironmentService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TABLES = List.of("system_config", "ai_model_provider",
            "knowledge_document", "knowledge_chunk", "business_fact", "service_project",
            "service_project_alias", "project_offer", "project_asset", "image_asset", "store", "store_project");
    // xmin/ctid 标识可见行版本，能检测未更新 update_time 的修改以及删除。
    // 只聚合行版本元数据，不读取向量正文；VACUUM FULL 等物理重写会保守地使旧凭证失效。
    static final String REVISIONS_SQL = TABLES.stream().map(table ->
            "SELECT '" + table + "' AS name, COALESCE(md5(string_agg(ctid::text || ':' || xmin::text, ',' ORDER BY ctid)), '') AS revision FROM " + table)
            .collect(Collectors.joining(" UNION ALL ")) + " ORDER BY name";

    private final JdbcTemplate jdbc;
    private final AiModelProviderService providers;
    private final ConfigService config;
    private final KeyConfigService keys;

    public HarnessEnvironmentService(JdbcTemplate jdbc, AiModelProviderService providers,
                                      ConfigService config, KeyConfigService keys) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.config = config;
        this.keys = keys;
    }

    public String fingerprint() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("harness", HarnessAssertions.VERSION);
        material.put("data", jdbc.queryForList(REVISIONS_SQL));
        material.put("primary", providers.active("primary"));
        material.put("fallback", providers.active("fallback"));
        Map<String, String> settings = new TreeMap<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            settings.put(setting.key(), config.get(setting.key(), setting.defaultValue()));
        }
        material.put("settings", settings);
        material.put("dashscopeKey", keys.get("ai.dashscope_key", "DASHSCOPE_API_KEY"));
        material.put("visionKey", keys.get("model.vision.apiKey", ""));
        material.put("embeddingKey", keys.get("model.embedding.apiKey", ""));
        AdminDataScope.AccessScope scope = AdminDataScope.current();
        material.put("scope", List.of(scope.restricted(), scope.tenantIds().stream().sorted().toList(),
                scope.kfAccountIds().stream().sorted().toList()));
        try {
            // 密钥只用于进程内哈希，不将原始配置持久化、返回前端或写日志。
            return PromptRegressionCaseCatalog.hash(JSON.writeValueAsString(material));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Harness 运行环境指纹", e);
        }
    }
}
