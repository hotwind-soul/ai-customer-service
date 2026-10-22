package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import com.example.aics.ai.EmbeddingModelSwitchService;
import com.example.aics.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统配置接口（受管理口令保护）：
 * GET  /config/{key}            读配置
 * PUT  /config/{key}            改配置（body: {"value": "..."}），立即生效
 * GET  /config/list             设置页配置清单（密钥类 + 业务热配置）
 * POST /config/reload           全量刷新缓存
 *
 * 常用 key：
 *   prompt.tone          客群话术要求（通过 Prompt 草稿/版本流程发布）
 *   refusal.answer       知识不足时的安全拒答话术（不可虚假声称已转人工）
 *   threshold.relevance  相关度门槛（默认 0.5）
 *   threshold.faqDirect  FAQ 直回门槛（默认 0.15）
 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private static final Set<String> EMBEDDING_MODEL_KEYS = Set.of(
            "model.embedding.baseUrl", "model.embedding.name");
    private static final Set<String> PROJECT_CATALOG_KEYS = Set.of(
            "intent.projects", "home.projectList");

    private final ConfigService configService;
    private final KeyConfigService keyConfigService;
    private final AuditService auditService;
    private final AgentAccountService agentAccountService;
    private final WebAuthCookieService authCookies;
    private final EmbeddingModelSwitchService embeddingSwitchService;
    private final PromptReleaseService promptReleaseService;
    private final PromptRegressionGateService promptRegressionGateService;

    public ConfigController(ConfigService configService, KeyConfigService keyConfigService,
                            AuditService auditService, AgentAccountService agentAccountService,
                            WebAuthCookieService authCookies,
                            EmbeddingModelSwitchService embeddingSwitchService,
                            PromptReleaseService promptReleaseService,
                            PromptRegressionGateService promptRegressionGateService) {
        this.configService = configService;
        this.keyConfigService = keyConfigService;
        this.auditService = auditService;
        this.agentAccountService = agentAccountService;
        this.authCookies = authCookies;
        this.embeddingSwitchService = embeddingSwitchService;
        this.promptReleaseService = promptReleaseService;
        this.promptRegressionGateService = promptRegressionGateService;
    }

    /** AI 配置中心：普通管理员只看到业务参数；高级模型参数仅超级管理员可见。 */
    @GetMapping("/ai-settings")
    public Map<String, Object> aiSettings(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        boolean superAdmin = AgentAccountService.ROLE_SUPER_ADMIN.equals(String.valueOf(session.get("role")));
        List<Map<String, Object>> items = AiConfigCatalog.responseItems(configService, superAdmin);
        return Map.of("items", items, "superAdmin", superAdmin);
    }

    /** 批量校验、原子保存普通配置并立即热生效；版本化 Prompt 必须走草稿/发布接口。 */
    @PutMapping("/ai-settings")
    public Map<String, Object> saveAiSettings(@RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        boolean superAdmin = AgentAccountService.ROLE_SUPER_ADMIN.equals(String.valueOf(session.get("role")));
        Object rawValues = body.get("values");
        if (!(rawValues instanceof Map<?, ?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("没有需要发布的 AI 配置");
        }
        if (values.size() > AiConfigCatalog.definitions().size()) {
            throw new IllegalArgumentException("一次发布的配置项过多");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((rawKey, rawValue) -> {
            String key = String.valueOf(rawKey);
            AiConfigCatalog.Setting setting = AiConfigCatalog.find(key);
            if (setting == null) throw new IllegalArgumentException("不支持的 AI 配置：" + key);
            if (setting.superOnly() && !superAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "仅超级管理员可以修改高级模型配置：" + setting.label());
            }
            if (EMBEDDING_MODEL_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "向量模型必须通过安全切换接口发布，系统会自动重建已有知识向量");
            }
            if (PROJECT_CATALOG_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "项目名称和别名必须在项目中心维护，发布后会自动同步全部入口");
            }
            if (AiConfigCatalog.isVersionedPromptKey(key)) {
                throw new IllegalArgumentException(
                        "Prompt 配置必须先保存草稿，再通过版本发布接口生效");
            }
            normalized.put(key, AiConfigCatalog.validateAndNormalize(key,
                    rawValue == null ? "" : String.valueOf(rawValue)));
        });
        Map<String, String> saved = configService.setAll(normalized);
        String actor = String.valueOf(session.getOrDefault("username", "admin"));
        auditService.log("UPDATE_AI_CONFIG", String.join(",", saved.keySet()), actor);
        return Map.of("result", "已发布并立即生效", "values", saved);
    }

    /** 当前线上 Prompt 版本和登录管理员自己的草稿。首次访问会建立基线快照。 */
    @GetMapping("/prompt-workspace")
    public Map<String, Object> promptWorkspace(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        return promptReleaseService.workspace(actor(session), isSuperAdmin(session));
    }

    /** 保存登录管理员自己的 Prompt 草稿，不影响线上对话。 */
    @PutMapping("/prompt-draft")
    public Map<String, Object> savePromptDraft(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        Object rawValues = body.get("values");
        if (!(rawValues instanceof Map<?, ?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("没有需要保存的 Prompt 草稿");
        }
        Map<String, Object> normalizedInput = new LinkedHashMap<>();
        values.forEach((key, value) -> normalizedInput.put(String.valueOf(key), value));
        Map<String, Object> result = promptReleaseService.saveDraft(
                actor(session), isSuperAdmin(session), normalizedInput);
        auditService.log("SAVE_PROMPT_DRAFT", String.join(",", normalizedInput.keySet()), actor(session));
        return result;
    }

    /** 放弃自己的 Prompt 草稿，不改变线上版本。 */
    @DeleteMapping("/prompt-draft")
    public Map<String, Object> discardPromptDraft(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        Map<String, Object> result = promptReleaseService.discardDraft(
                actor(session), isSuperAdmin(session));
        auditService.log("DISCARD_PROMPT_DRAFT", "", actor(session));
        return result;
    }

    /** 原子发布自己的 Prompt 草稿并生成不可变历史版本。 */
    @PostMapping("/prompt-release")
    public Map<String, Object> publishPromptDraft(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        Map<String, Object> result = promptReleaseService.publishDraft(
                actor(session), isSuperAdmin(session));
        auditService.log("PUBLISH_PROMPT", String.valueOf(result.get("release")), actor(session));
        return result;
    }

    /** 后台运行全部启用回归用例；通过结果与当前草稿和用例集指纹绑定。 */
    @PostMapping("/prompt-regression-gate")
    public Map<String, Object> runPromptRegressionGate(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        return promptRegressionGateService.submit(actor(session), isSuperAdmin(session));
    }

    @GetMapping("/prompt-regression-gate/status")
    public Map<String, Object> promptRegressionGateStatus(@RequestParam long taskId,
                                                          HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        return promptRegressionGateService.status(taskId, actor(session));
    }

    @GetMapping("/prompt-regression-gate/latest")
    public Map<String, Object> latestPromptRegressionGate(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        return promptRegressionGateService.latest(actor(session), isSuperAdmin(session));
    }

    @GetMapping("/prompt-releases")
    public List<Map<String, Object>> promptReleaseHistory(
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        return promptReleaseService.history(limit, isSuperAdmin(session));
    }

    @GetMapping("/prompt-releases/{releaseId}")
    public Map<String, Object> promptReleaseDetail(@PathVariable long releaseId,
                                                   HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        return promptReleaseService.releaseDetail(releaseId, isSuperAdmin(session));
    }

    /** 回滚也创建一个新版本，不删除或改写已有历史。 */
    @PostMapping("/prompt-releases/{releaseId}/rollback")
    public Map<String, Object> rollbackPrompt(@PathVariable long releaseId,
                                              HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        Map<String, Object> result = promptReleaseService.rollback(
                releaseId, actor(session), isSuperAdmin(session));
        auditService.log("ROLLBACK_PROMPT", "source=" + releaseId, actor(session));
        return result;
    }

    public record EmbeddingSwitchRequest(String baseUrl, String modelName, String apiKey) {}

    /** 候选模型先全量重建，成功后才原子切换；接口立即返回任务 ID 供前端轮询。 */
    @PostMapping("/embedding-model/switch")
    public Map<String, Object> switchEmbeddingModel(@RequestBody EmbeddingSwitchRequest body,
                                                    HttpServletRequest request) {
        Map<String, Object> session = requireSuperAdmin(request);
        String baseUrl = AiConfigCatalog.validateAndNormalize(
                "model.embedding.baseUrl", body == null ? "" : body.baseUrl());
        String modelName = AiConfigCatalog.validateAndNormalize(
                "model.embedding.name", body == null ? "" : body.modelName());
        String actor = String.valueOf(session.getOrDefault("username", "admin"));
        long taskId = embeddingSwitchService.submit(
                baseUrl, modelName, body == null ? "" : body.apiKey(), actor);
        auditService.log("SWITCH_EMBEDDING_MODEL_SUBMIT", modelName + " taskId=" + taskId, actor);
        return Map.of("taskId", taskId, "status", "queued",
                "message", "候选模型正在验证并重建已有知识，成功后会自动切换");
    }

    @GetMapping("/embedding-model/switch/status")
    public Map<String, Object> embeddingModelSwitchStatus(@RequestParam long taskId,
                                                           HttpServletRequest request) {
        requireSuperAdmin(request);
        return embeddingSwitchService.status(taskId);
    }

    @GetMapping("/{key}")
    public Map<String, Object> get(@PathVariable String key, HttpServletRequest request) {
        if (keyConfigService.isSecretKey(key)) {
            String[] meta = keyConfigService.knownKeys().get(key);
            String env = meta.length > 3 ? meta[3] : "";
            return Map.of(
                    "key", key,
                    "value", "",
                    "secret", true,
                    "configured", keyConfigService.has(key, env));
        }
        AiConfigCatalog.Setting setting = AiConfigCatalog.find(key);
        if (setting == null) throw new IllegalArgumentException("不支持的配置项：" + key);
        ensureSettingAccess(setting, request);
        return Map.of("key", key, "value", configService.get(key, setting.defaultValue()));
    }

    @PutMapping("/{key}")
    public Map<String, String> set(@PathVariable String key, @RequestBody Map<String, String> body,
                                   HttpServletRequest request) {
        String value = body.getOrDefault("value", "");
        if (keyConfigService.isSecretKey(key)) {
            keyConfigService.set(key, value);
            auditService.log(value.isBlank() ? "CLEAR_SECRET" : "UPDATE_SECRET", key, "admin");
            return Map.of("key", key, "value", "", "result", "已安全保存", "secret", "true");
        }
        AiConfigCatalog.Setting setting = AiConfigCatalog.find(key);
        if (setting == null) throw new IllegalArgumentException("不支持的配置项：" + key);
        ensureSettingAccess(setting, request);
        if (EMBEDDING_MODEL_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "向量模型必须通过安全切换接口发布，系统会自动重建已有知识向量");
        }
        if (PROJECT_CATALOG_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "项目名称和别名必须在项目中心维护，发布后会自动同步全部入口");
        }
        if (AiConfigCatalog.isVersionedPromptKey(key)) {
            throw new IllegalArgumentException(
                    "Prompt 配置必须先保存草稿，再通过版本发布接口生效");
        }
        value = AiConfigCatalog.validateAndNormalize(key, value);
        configService.set(key, value);
        auditService.log("UPDATE_CONFIG", key, "admin");
        return Map.of("key", key, "value", value, "result", "已生效");
    }

    /** 设置页清单：密钥类（key/显示名/分组/DB值/配置状态/来源） */
    @GetMapping("/list")
    public Map<String, Object> list() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        java.util.List<Map<String, Object>> keys = new java.util.ArrayList<>();
        keyConfigService.knownKeys().forEach((key, meta) -> {
            String env = meta.length > 3 ? meta[3] : "";
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("key", key);
            item.put("label", meta[0]);
            item.put("group", meta[1]);
            item.put("desc", meta[2]);
            item.put("value", ""); // 密钥只写：浏览器永远拿不到 DB 密文或明文
            item.put("secret", true);
            boolean fromEnv = !keyConfigService.hasStoredValue(key)
                    && env != null && !env.isBlank()
                    && System.getenv(env) != null && !System.getenv(env).isBlank();
            item.put("configured", keyConfigService.has(key, env));
            item.put("fromEnv", fromEnv);
            keys.add(item);
        });
        result.put("keys", keys);
        return result;
    }

    @PostMapping("/reload")
    public Map<String, String> reload() {
        configService.reload();
        return Map.of("result", "缓存已刷新");
    }

    /** 仅超级管理员：查看已保存密钥的明文（每次查看都写审计日志）。 */
    @GetMapping("/secret/reveal")
    public Map<String, Object> revealSecret(@RequestParam String key, HttpServletRequest request) {
        Map<String, Object> session = requireSuperAdmin(request);
        if (!keyConfigService.isSecretKey(key)) {
            throw new IllegalArgumentException("不是受支持的密钥配置：" + key);
        }
        auditService.log("REVEAL_SECRET", key,
                String.valueOf(session.getOrDefault("username", "admin")));
        return Map.of("key", key, "value", keyConfigService.reveal(key));
    }

    private Map<String, Object> requireSession(HttpServletRequest request) {
        Map<String, Object> session = agentAccountService.adminSession(authCookies.adminToken(request));
        if (session == null || session.isEmpty()) throw new IllegalArgumentException("管理员登录已失效");
        return session;
    }

    private Map<String, Object> requireSuperAdmin(HttpServletRequest request) {
        Map<String, Object> session = requireSession(request);
        if (!AgentAccountService.ROLE_SUPER_ADMIN.equals(String.valueOf(session.get("role")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅超级管理员可以切换向量模型");
        }
        return session;
    }

    private boolean isSuperAdmin(Map<String, Object> session) {
        return AgentAccountService.ROLE_SUPER_ADMIN.equals(String.valueOf(session.get("role")));
    }

    private String actor(Map<String, Object> session) {
        return String.valueOf(session.getOrDefault("username", "admin"));
    }

    private void ensureSettingAccess(AiConfigCatalog.Setting setting, HttpServletRequest request) {
        if (!setting.superOnly()) return;
        Map<String, Object> session = requireSession(request);
        if (!AgentAccountService.ROLE_SUPER_ADMIN.equals(String.valueOf(session.get("role")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "仅超级管理员可以访问高级模型配置：" + setting.label());
        }
    }
}
