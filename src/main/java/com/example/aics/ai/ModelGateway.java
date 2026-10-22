package com.example.aics.ai;

import com.example.aics.config.KeyConfigService;
import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.ConfigService;
import com.example.aics.chat.ChatPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型网关（ADR-002）：全系统唯一的模型出口。
 *
 * 收敛之前散落在 ChatController / AgentService / ChatWebSocketHandler / KnowledgeService
 * 的四份模型客户端，统一：
 * 1. 主备切换：由超级管理员在模型管理页指定，调用异常/超时自动降级；
 * 2. 统一超时：主备都走 LangChain4j 权威 .timeout()（connect+read 同时生效，
 *    见 2026-08-07 卡死事故结论——不要绕 httpClientBuilder）；
 * 3. 指标：每次调用计数（按模型/用途）、降级次数、最近降级时间，GET /api/metrics 可查。
 *
 * 私有化演进：未来换 vLLM 私有部署只改这里两个 baseUrl/modelName，业务代码零改动。
 */
@Service
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> QUALITY_SCORE_FIELDS = List.of(
            "intent", "accuracy", "context", "safety", "naturalness", "resolution", "handoff");

    /** 当前主模型（同步）—— volatile：管理后台修改后可重建热生效 */
    private volatile OpenAiChatModel primaryChat;
    /** 当前备用模型（同步） */
    private volatile OpenAiChatModel fallbackChat;
    /** 离线评测独立客户端：使用低随机度，不复用客服回复参数。 */
    private volatile OpenAiChatModel qualityPrimaryChat;
    private volatile OpenAiChatModel qualityFallbackChat;
    /** 当前主模型（流式） */
    private volatile OpenAiStreamingChatModel primaryStreaming;
    /** 当前备用模型（流式） */
    private volatile OpenAiStreamingChatModel fallbackStreaming;
    private volatile AiModelProviderService.RuntimeModel primaryModel;
    private volatile AiModelProviderService.RuntimeModel fallbackModel;
    private volatile AiModelProviderService.RuntimeModel evalModel;
    private volatile OpenAiChatModel qualityEvalChat;
    /** Embedding：通义 text-embedding-v3（与知识库向量保持同一模型） */
    private volatile OpenAiEmbeddingModel embeddingModel;
    /** 视觉模型：通义 qwen-vl-max（OCR 识图，走百炼，无主备） */
    private volatile OpenAiChatModel visionModel;

    /** 密钥来源（DB 优先 + 环境变量回退，管理后台设置页可改） */
    private final KeyConfigService keys;
    private final ConfigService config;
    private final AiModelProviderService providers;
    /** 最近一次构建使用的模型配置签名（检测变化触发重建） */
    private volatile String builtProviderSignature = "";
    private volatile String builtDashscopeKey = "";
    private volatile String builtVisionApiKey = "";
    private volatile String builtEmbeddingApiKey = "";
    private volatile String builtConfigSignature = "";

    /** 指标：用途/模型 → 调用次数 */
    private final Map<String, AtomicLong> callCounts = new ConcurrentHashMap<>();
    /** 指标：主→备降级次数 */
    private final AtomicLong failoverCount = new AtomicLong();
    /** 指标：最近一次降级时间戳（0 = 从未降级） */
    private volatile long lastFailoverAt = 0;
    /** 主模型连续失败熔断，避免额度耗尽时每一条消息都先等待主模型超时。 */
    private final AtomicInteger primaryConsecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitBypassCount = new AtomicLong();
    private volatile long primaryCircuitOpenUntil = 0;

    // ==================== 趋势时间序列（按分钟分桶的滑动窗口） ====================
    /** 桶大小：60 秒。窗口：180 桶 = 最近 3 小时。 */
    private static final long TREND_BUCKET_MS = 60_000L;
    private static final int TREND_WINDOW_BUCKETS = 180;
    private final Map<Long, TrendBucket> trendBuckets = new ConcurrentHashMap<>();
    /** 单分钟桶：缓存命中/查询、主→备降级次数、各模型调用次数（按 <模型>.<用途> 计）。 */
    private static final class TrendBucket {
        final AtomicLong cacheHit = new AtomicLong();
        final AtomicLong cacheLookups = new AtomicLong();
        final AtomicLong failover = new AtomicLong();
        final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();
    }
    private TrendBucket currentTrendBucket() {
        long now = System.currentTimeMillis();
        long start = (now / TREND_BUCKET_MS) * TREND_BUCKET_MS;
        TrendBucket b = trendBuckets.get(start);
        if (b == null) {
            b = trendBuckets.computeIfAbsent(start, k -> new TrendBucket());
            long cutoff = start - TREND_WINDOW_BUCKETS * TREND_BUCKET_MS;
            trendBuckets.keySet().removeIf(k -> k < cutoff);
        }
        return b;
    }

    // ==================== 响应缓存（降本） ====================
    /**
     * 相同 Prompt 的回答缓存：prompt 经 SHA-256 哈希后作 key，value 带过期时间。
     * 命中即直接复用，跳过主/备模型调用，显著降低按 token 计费模型成本。
     * 仅缓存「完整成功返回」的回答；部分流式失败不缓存。key 含完整上下文（含历史），
     * 因此只在真正相同 Prompt 时命中，不会用旧上下文编造答案。
     */
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();
    /** 每次重建隔离缓存；旧的在途请求即使稍后完成，也不能写回新模型的缓存命名空间。 */
    private final AtomicLong responseCacheGeneration = new AtomicLong();
    private static final class CacheEntry {
        final String answer;
        final long expireAt;
        CacheEntry(String answer, long ttlMillis) {
            this.answer = answer;
            this.expireAt = System.currentTimeMillis() + ttlMillis;
        }
        boolean alive() { return System.currentTimeMillis() < expireAt; }
    }

    public ModelGateway(KeyConfigService keys, ConfigService config, AiModelProviderService providers) {
        this.keys = keys;
        this.config = config;
        this.providers = providers;
        rebuild();   // 启动即构建（key 未配时模型可用但调用会失败，W25 已提示）
    }

    /**
     * 模型自测客户端缓存：testProvider 每次点击原本会新建 2 个 OpenAiChatModel（各带独立 HttpClient），
     * 而 langchain4j 1.17.2 的模型类没有 close()（已用 javap 核实），无法主动释放旧实例；
     * 用固定上限的缓存复用，把常驻实例数压住。键含完整配置，配置变了自然 miss 并替换。
     */
    private final Map<String, OpenAiChatModel> testClients = new ConcurrentHashMap<>();

    // ==================== Prometheus 可观测性（ADR-002 配套） ====================
    /**
     * 把网关的自定义计数桥接进 Micrometer，使 /actuator/prometheus 能抓到 AI 侧指标。
     * 此前该端点只有 JVM/HTTP/连接池——降级频发、熔断打开、token 异常消耗这些业务故障
     * 在监控上完全不可见，出了问题只能靠客户投诉发现。
     * actuator 不在 classpath 时 registry 为 null，所有计数行为不受影响。
     */
    private volatile MeterRegistry meterRegistry;
    /** 已注册过 Prometheus 指标的计数键（避免重复注册；新键出现时才注册一次）。 */
    private final Set<String> prometheusBound = ConcurrentHashMap.newKeySet();

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        this.meterRegistry = registry;
        if (registry == null) return;
        FunctionCounter.builder("aics_failover_total", failoverCount, AtomicLong::get)
                .description("主模型失败后切换到备用模型的总次数")
                .register(registry);
        FunctionCounter.builder("aics_circuit_bypass_total", circuitBypassCount, AtomicLong::get)
                .description("熔断打开期间直接走备用模型的次数")
                .register(registry);
        FunctionCounter.builder("aics_primary_consecutive_failures", primaryConsecutiveFailures, AtomicInteger::get)
                .description("主模型连续失败次数")
                .register(registry);
        Gauge.builder("aics_primary_circuit_open", this, g -> g.primaryCircuitOpen() ? 1.0 : 0.0)
                .description("主模型熔断是否处于打开状态（1=打开，0=正常）")
                .register(registry);
    }

    /**
     * 把计数键映射为 Prometheus 指标名与标签；无法识别的键归入 aics_counter_total{key=...}。
     * 注意：<model>.<action> 形态的键（如 deepseek-chat.chat）映射为带 model/action 标签的调用计数。
     */
    private void registerPrometheusCounter(String key, AtomicLong value) {
        if (meterRegistry == null) return;
        if (!prometheusBound.add(key)) return;
        try {
            String name = "aics_counter_total";
            String model = null, action = null, result = null, source = null, method = null, raw = null;
            if (key.startsWith("token.input.")) {
                name = "aics_token_input_total"; model = key.substring("token.input.".length());
            } else if (key.startsWith("token.output.")) {
                name = "aics_token_output_total"; model = key.substring("token.output.".length());
            } else if (key.equals("faq.bypass.hit") || key.equals("faq.bypass.eligible")) {
                name = "aics_faq_bypass_total"; result = key.substring("faq.bypass.".length());
            } else if (key.equals("retrieval.vector.candidates") || key.equals("retrieval.keyword.candidates")) {
                name = "aics_retrieval_candidates_total";
                source = key.substring("retrieval.".length()).replace(".candidates", "");
            } else if (key.startsWith("retrieval.source.")) {
                name = "aics_retrieval_source_total"; method = key.substring("retrieval.source.".length());
            } else if (key.startsWith("retrieval.rerank.")) {
                name = "aics_rerank_total"; result = key.substring("retrieval.rerank.".length());
            } else if (key.equals("response_cache.hit") || key.equals("response_cache.lookups")) {
                name = "aics_response_cache_total"; result = key.equals("response_cache.hit") ? "hit" : "lookup";
            } else {
                int dot = key.lastIndexOf('.');
                if (dot > 0 && dot < key.length() - 1) {
                    model = key.substring(0, dot); action = key.substring(dot + 1); name = "aics_model_calls_total";
                } else {
                    raw = key;
                }
            }
            FunctionCounter.Builder builder = FunctionCounter.builder(name, value, AtomicLong::get)
                    .description("AI 网关自定义计数");
            if (model != null) builder.tag("model", model);
            if (action != null) builder.tag("action", action);
            if (result != null) builder.tag("result", result);
            if (source != null) builder.tag("source", source);
            if (method != null) builder.tag("method", method);
            if (raw != null) builder.tag("key", raw);
            builder.register(meterRegistry);
        } catch (Exception e) {
            log.warn("注册 Prometheus 指标失败（key={}）：{}", key, e.getMessage());
            prometheusBound.remove(key);   // 注册失败允许下次重试
        }
    }

    /** 用当前密钥重建全部模型（网页改 key 后 ensureModels 检测到变化自动调用） */
    private synchronized void rebuild() {
        String dashscopeKey = keys.get("ai.dashscope_key", "DASHSCOPE_API_KEY");
        AiModelProviderService.RuntimeModel primary = providers.active("primary");
        AiModelProviderService.RuntimeModel fallback = providers.active("fallback");
        if (primary.apiKey().isBlank()) log.warn("当前主模型 {} 尚未配置 API Key", primary.displayName());
        if (fallback.apiKey().isBlank()) log.warn("当前备用模型 {} 尚未配置 API Key", fallback.displayName());
        if (dashscopeKey.isBlank()
                && keys.get("model.vision.apiKey", "").isBlank()
                && keys.get("model.embedding.apiKey", "").isBlank()) {
            log.warn("视觉/向量模型未配置任何 API Key（独立 key 或通义 ai.dashscope_key 兜底），对应功能将不可用");
        }
        String embeddingBaseUrl = setting("model.embedding.baseUrl");
        String embeddingName = setting("model.embedding.name");
        String visionBaseUrl = setting("model.vision.baseUrl");
        String visionName = setting("model.vision.name");
        double temperature = config.getDouble("model.temperature", 0.3);
        double judgeTemperature = Math.max(0.0,
                Math.min(config.getDouble("quality.judgeTemperature", 0.0), 0.2));
        Duration chatTimeout = Duration.ofSeconds(config.getInt("model.chatTimeoutSeconds", 30));
        Duration judgeTimeout = Duration.ofSeconds(Math.max(5,
                Math.min(config.getInt("quality.judgeTimeoutSeconds", 30), 120)));
        Duration streamTimeout = Duration.ofSeconds(config.getInt("model.streamTimeoutSeconds", 60));
        this.primaryChat = OpenAiChatModel.builder()
                .baseUrl(primary.baseUrl())
                .apiKey(primary.apiKey())
                .modelName(primary.modelName())
                .temperature(temperature)
                .timeout(chatTimeout)
                .maxRetries(0) // 重试由网关主备切换负责，避免 SDK 默认重试成倍拉长等待。
                .build();
        this.fallbackChat = OpenAiChatModel.builder()
                .baseUrl(fallback.baseUrl())
                .apiKey(fallback.apiKey())
                .modelName(fallback.modelName())
                .temperature(temperature)
                .timeout(chatTimeout)
                .maxRetries(0)
                .build();
        this.qualityPrimaryChat = OpenAiChatModel.builder()
                .baseUrl(primary.baseUrl())
                .apiKey(primary.apiKey())
                .modelName(primary.modelName())
                .temperature(judgeTemperature)
                .timeout(judgeTimeout)
                .maxRetries(0)
                .build();
        this.qualityFallbackChat = OpenAiChatModel.builder()
                .baseUrl(fallback.baseUrl())
                .apiKey(fallback.apiKey())
                .modelName(fallback.modelName())
                .temperature(judgeTemperature)
                .timeout(judgeTimeout)
                .maxRetries(0)
                .build();
        this.primaryStreaming = OpenAiStreamingChatModel.builder()
                .baseUrl(primary.baseUrl())
                .apiKey(primary.apiKey())
                .modelName(primary.modelName())
                .temperature(temperature)
                .timeout(streamTimeout)
                .build();
        this.fallbackStreaming = OpenAiStreamingChatModel.builder()
                .baseUrl(fallback.baseUrl())
                .apiKey(fallback.apiKey())
                .modelName(fallback.modelName())
                .temperature(temperature)
                .timeout(streamTimeout)
                .build();
        // 视觉 / 向量模型密钥：优先用各自单独配置的密钥，未配置时按 baseUrl 回退到通义 Key（兼容历史）。
        String visionApiKey = fallbackApiKey(visionBaseUrl, keys.get("model.vision.apiKey", ""), dashscopeKey);
        String embeddingApiKey = fallbackApiKey(embeddingBaseUrl, keys.get("model.embedding.apiKey", ""), dashscopeKey);
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingName)
                .timeout(streamTimeout)
                .build();
        this.visionModel = OpenAiChatModel.builder()
                .baseUrl(visionBaseUrl)
                .apiKey(visionApiKey)
                .modelName(visionName)
                .temperature(temperature)
                .timeout(streamTimeout)
                .build();
        this.primaryModel = primary;
        this.fallbackModel = fallback;
        AiModelProviderService.RuntimeModel eval = providers.activeOptional("eval").orElse(null);
        this.evalModel = eval;
        this.qualityEvalChat = (eval != null && !eval.apiKey().isBlank())
                ? OpenAiChatModel.builder().baseUrl(eval.baseUrl()).apiKey(eval.apiKey())
                    .modelName(eval.modelName()).temperature(judgeTemperature).timeout(judgeTimeout)
                    .maxRetries(0).build()
                : null;
        if (eval != null && qualityEvalChat == null) {
            log.warn("评测专用模型 {} 尚未配置 API Key，质量复核的 eval 方式将不可用", eval.displayName());
        }
        // 管理员切换或修复主模型后立即恢复试探，不能沿用旧主模型的熔断冷却时间。
        primarySucceeded();
        // 先切换缓存代次，再发布构建签名，避免并发请求看到新签名却命中旧缓存。
        responseCacheGeneration.incrementAndGet();
        responseCache.clear();
        this.builtProviderSignature = providers.configurationSignature();
        this.builtDashscopeKey = dashscopeKey;
        this.builtVisionApiKey = visionApiKey;
        this.builtEmbeddingApiKey = embeddingApiKey;
        this.builtConfigSignature = configSignature();
    }

    /** 调用前检查 key 是否变化（管理后台设置页改了 key → 下次调用自动重建，无需重启） */
    private void ensureModels() {
        String qk = keys.get("ai.dashscope_key", "DASHSCOPE_API_KEY");
        String visionApiKey = fallbackApiKey(setting("model.vision.baseUrl"), keys.get("model.vision.apiKey", ""), qk);
        String embeddingApiKey = fallbackApiKey(setting("model.embedding.baseUrl"), keys.get("model.embedding.apiKey", ""), qk);
        String providerSignature = providers.configurationSignature();
        String signature = configSignature();
        if (!Objects.equals(providerSignature, builtProviderSignature)
                || !Objects.equals(qk, builtDashscopeKey)
                || !Objects.equals(visionApiKey, builtVisionApiKey)
                || !Objects.equals(embeddingApiKey, builtEmbeddingApiKey)
                || !Objects.equals(signature, builtConfigSignature)) {
            log.info("[ModelGateway] 检测到密钥或模型配置变化，重建模型实例");
            rebuild();
        }
    }

    // ==================== 响应缓存辅助 ====================
    /** 命中这些标记的回答视为"模型未真正接住"，不进缓存。 */
    private static final java.util.List<String> NON_ANSWER_MARKERS = java.util.List.of(
            "没收到", "没发出来", "请问有什么可以帮您", "再说一遍", "没听清", "问题好像",
            "AI 服务暂时不可用", "请求处理失败");

    /** 模型看似成功返回、实际没有回答问题；这种软失败和超时一样应触发备用模型。 */
    static boolean isNonAnswerAnswer(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String compact = answer.replaceAll("\\s+", " ").trim();
        String probe = compact.substring(0, Math.min(compact.length(), 160));
        return NON_ANSWER_MARKERS.stream().anyMatch(probe::contains);
    }
    private boolean cacheEnabled() {
        return config.getBoolean("model.responseCacheEnabled", true);
    }
    private long cacheTtlMillis() {
        return Math.max(0, config.getInt("model.responseCacheTtlSeconds", 180)) * 1000L;
    }
    private int cacheMaxEntries() {
        return Math.max(100, config.getInt("model.responseCacheMaxEntries", 2000));
    }
    private String cacheKey(String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(prompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
    private String cachedAnswer(String key) {
        if (!cacheEnabled()) return null;
        long ttl = cacheTtlMillis();
        if (ttl <= 0) return null;
        CacheEntry e = responseCache.get(key);
        if (e == null) return null;
        if (!e.alive() || isNonAnswerAnswer(e.answer)) { responseCache.remove(key, e); return null; }
        return e.answer;
    }
    /**
     * 只缓存"看起来是真实有效回答"的结果。空泛非回答（如模型退化出的"没收到您的问题"）、
     * 拒答、转人工、错误兜底都不要进缓存，否则会把坏答案按相同 prompt 复用 180s，放大问题。
     */
    private boolean isCacheableAnswer(String answer) {
        if (answer == null) return false;
        String a = answer.trim();
        if (a.length() < 8) return false;
        // 空泛"没接住"话术：模型未真正理解/检索到内容时的退化输出
        return !isNonAnswerAnswer(a);
    }

    private String controlledNonAnswerFallback() {
        String fallback = config.get("refusal.answer",
                "这个问题我暂时没有查到可靠资料，为避免给您错误信息，请换个方式描述或回复“转人工”联系客服确认。");
        if (fallback == null || fallback.isBlank()
                || fallback.contains("已为您转接") || fallback.contains("已转人工")) {
            return "这个问题我暂时没有查到可靠资料，为避免给您错误信息，请换个方式描述或回复“转人工”联系客服确认。";
        }
        return fallback;
    }

    private String fallbackChat(ChatRequest request, String cacheMaterial) {
        count(fallbackModel.modelName() + ".chat", 1);
        ChatResponse resp = fallbackChat.chat(request);
        recordTokenUsage(fallbackModel.modelName(), resp);
        String answer = responseText(resp);
        if (isNonAnswerAnswer(answer)) {
            count(fallbackModel.modelName() + ".non-answer", 1);
            log.warn("备用模型 {} 返回空泛非回答，改用受控知识边界话术", fallbackModel.modelName());
            return controlledNonAnswerFallback();
        }
        putCache(cacheMaterial, answer);
        return answer;
    }

    private void putCache(String key, String answer) {
        if (!cacheEnabled()) return;
        long ttl = cacheTtlMillis();
        if (ttl <= 0 || answer == null) return;
        if (!isCacheableAnswer(answer)) return;
        responseCache.put(key, new CacheEntry(answer, ttl));
        int max = cacheMaxEntries();
        if (responseCache.size() > max) {
            int remove = (int) (max * 0.2) + 1;
            Iterator<String> it = responseCache.keySet().iterator();
            int n = 0;
            while (it.hasNext() && n < remove) { it.remove(); n++; }
        }
    }

    private String setting(String key) {
        return config.get(key, AiConfigCatalog.defaultValue(key));
    }

    private String configSignature() {
        return String.join("\u001f",
                setting("model.embedding.baseUrl"), setting("model.embedding.name"),
                setting("model.vision.baseUrl"), setting("model.vision.name"),
                config.get("model.temperature", "0.3"),
                config.get("quality.judgeTemperature", "0"),
                config.get("quality.judgeTimeoutSeconds", "30"),
                config.get("model.chatTimeoutSeconds", "30"),
                config.get("model.streamTimeoutSeconds", "60"),
                config.get("model.circuitFailureThreshold", "3"),
                config.get("model.circuitCooldownSeconds", "120"));
    }

    /** 兼容历史配置：若备用地址仍指向百炼，则继续复用通义 Key。 */
    static String fallbackApiKey(String baseUrl, String configuredFallbackKey, String dashscopeKey) {
        if (configuredFallbackKey != null && !configuredFallbackKey.isBlank()) return configuredFallbackKey;
        if (baseUrl != null && baseUrl.toLowerCase(java.util.Locale.ROOT).contains("dashscope")) {
            return dashscopeKey == null ? "" : dashscopeKey;
        }
        return "";
    }

    // ==================== 同步问答（主备切换） ====================

    /** 同步问答：当前主模型额度不足、限流、超时或异常时自动降级到当前备用模型。 */
    public String chat(String prompt) {
        return chat(ChatPrompt.legacy(prompt));
    }

    /** 分层问答：真正以 system/user 角色发送，不再将系统规则混入普通用户字符串。 */
    public String chat(ChatPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ensureModels();
        ChatRequest request = chatRequest(prompt);
        String cacheMaterial = cacheKey(responseCacheGeneration.get() + ":" + prompt.cacheMaterial());
        count("response_cache.lookups", 1);
        String cached = cachedAnswer(cacheMaterial);
        if (cached != null) {
            count("response_cache.hit", 1);
            return cached;
        }
        if (primaryCircuitOpen()) {
            circuitBypassCount.incrementAndGet();
            return fallbackChat(request, cacheMaterial);
        }
        String answer;
        count(primaryModel.modelName() + ".chat", 1);
        try {
            ChatResponse resp = primaryChat.chat(request);
            recordTokenUsage(primaryModel.modelName(), resp);
            answer = responseText(resp);
        } catch (Exception e) {
            log.warn("主模型调用失败，降级到备用模型：{}", e.getMessage());
            primaryFailed();
            failover();
            return fallbackChat(request, cacheMaterial);
        }
        // 备用调用必须在主模型的 try/catch 之外，防止把备用异常再次当成主模型失败。
        if (isNonAnswerAnswer(answer)) {
            count(primaryModel.modelName() + ".non-answer", 1);
            log.warn("主模型 {} 返回空泛非回答，降级到备用模型", primaryModel.modelName());
            primaryFailed();
            failover();
            return fallbackChat(request, cacheMaterial);
        }
        primarySucceeded();
        putCache(cacheMaterial, answer);
        return answer;
    }

    /**
     * 严格结构化输出：优先要求 JSON Schema；模型明确不支持 schema 时，
     * 只降级为 JSON object 模式。返回内容仍必须由业务层做完整 JSON 和字段校验。
     */
    public String chatStructured(String system, String userJson, JsonSchema schema) {
        Objects.requireNonNull(schema, "schema");
        ensureModels();
        ChatRequest strict = structuredRequest(system, userJson,
                ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(schema).build());
        ChatRequest jsonOnly = structuredRequest(system, userJson, ResponseFormat.JSON);
        if (primaryCircuitOpen()) {
            circuitBypassCount.incrementAndGet();
            return structuredWith(fallbackChat, fallbackModel.modelName(), strict, jsonOnly);
        }
        try {
            String answer = structuredWith(primaryChat, primaryModel.modelName(), strict, jsonOnly);
            primarySucceeded();
            return answer;
        } catch (Exception e) {
            log.warn("主模型结构化调用失败，降级到备用模型：{}", e.getMessage());
            primaryFailed();
            failover();
            return structuredWith(fallbackChat, fallbackModel.modelName(), strict, jsonOnly);
        }
    }

    private String structuredWith(OpenAiChatModel model, String label,
                                  ChatRequest strict, ChatRequest jsonOnly) {
        try {
            count(label + ".structured", 1);
            String answer = responseText(model.chat(strict));
            if (answer == null || answer.isBlank()) throw new IllegalStateException("模型返回空 JSON");
            return answer;
        } catch (RuntimeException e) {
            if (!structuredOutputUnsupported(e)) throw e;
            log.info("模型 {} 不支持 JSON Schema，降级为 JSON object 模式", label);
            count(label + ".structured-json-fallback", 1);
            String answer = responseText(model.chat(jsonOnly));
            if (answer == null || answer.isBlank()) throw new IllegalStateException("模型返回空 JSON");
            return answer;
        }
    }

    static boolean structuredOutputUnsupported(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                boolean formatMentioned = lower.contains("json_schema")
                        || lower.contains("json schema")
                        || lower.contains("response_format")
                        || lower.contains("structured output");
                boolean rejected = lower.contains("not support") || lower.contains("unsupported")
                        || lower.contains("invalid") || lower.contains("400")
                        || lower.contains("bad request");
                if (formatMentioned && rejected) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ChatRequest chatRequest(ChatPrompt prompt) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (!prompt.system().isBlank()) messages.add(SystemMessage.from(prompt.system()));
        messages.add(UserMessage.from(prompt.requestText()));
        return ChatRequest.builder().messages(messages).build();
    }

    private static ChatRequest structuredRequest(String system, String userJson,
                                                 ResponseFormat responseFormat) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (system != null && !system.isBlank()) messages.add(SystemMessage.from(system.strip()));
        messages.add(UserMessage.from(userJson == null ? "{}" : userJson.strip()));
        return ChatRequest.builder().messages(messages).responseFormat(responseFormat).build();
    }

    private static String responseText(ChatResponse response) {
        return response == null || response.aiMessage() == null ? null : response.aiMessage().text();
    }

    /**
     * 离线质量复核专用：按管理员本次选择只调用指定模型。
     * 这里故意不复用 chat()，从而保证主模型失败时不会切到备用模型，反之亦然。
     * 异常直接抛回，由评分服务保留规则分兜底。
     */
    public QualityJudgeResponse qualityJudgeChat(String prompt, String provider) {
        ensureModels();
        String selected = normalizeQualityJudgeProvider(provider);
        String modelName;
        String answer;
        if ("primary".equals(selected)) {
            modelName = primaryModel.modelName();
            answer = qualityPrimaryChat.chat(prompt);
        } else if ("eval".equals(selected)) {
            if (qualityEvalChat == null) {
                throw new IllegalStateException("评测专用模型尚未配置，请在「模型管理」添加评测专用模型后再使用此方式");
            }
            modelName = evalModel.modelName();
            answer = qualityEvalChat.chat(prompt);
        } else {
            modelName = fallbackModel.modelName();
            answer = qualityFallbackChat.chat(prompt);
        }
        count(modelName + ".quality-judge", 1);
        return new QualityJudgeResponse(answer, selected, modelName);
    }

    /** 返回管理页面可选的评测模型名称，不包含接口地址或密钥。 */
    public List<QualityJudgeModel> qualityJudgeModels() {
        ensureModels();
        List<QualityJudgeModel> models = new ArrayList<>();
        models.add(new QualityJudgeModel("primary", "主模型 · " + primaryModel.displayName(),
                primaryModel.displayName(), primaryModel.modelName()));
        models.add(new QualityJudgeModel("fallback", "备用模型 · " + fallbackModel.displayName(),
                fallbackModel.displayName(), fallbackModel.modelName()));
        if (evalModel != null) {
            models.add(new QualityJudgeModel("eval", "评测专用 · " + evalModel.displayName(),
                    evalModel.displayName(), evalModel.modelName()));
        }
        return models;
    }

    private String normalizeQualityJudgeProvider(String provider) {
        String selected = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("primary", "fallback", "eval").contains(selected)) {
            throw new IllegalArgumentException("评测模型只能选择主模型、备用模型或评测专用模型");
        }
        return selected;
    }

    public record QualityJudgeModel(String provider, String label, String displayName, String modelName) {}
    public record QualityJudgeResponse(String content, String provider, String modelName) {}

    // ==================== 流式问答（主备切换） ====================

    /**
     * 流式问答：先在网关内缓冲完整回复，通过空泛回答检测后再交给消费方。
     * 主模型异常或返回非回答时，客户尚未收到任何内容，可以安全地由备用模型完整重跑。
     */
    public void stream(String prompt, StreamingChatResponseHandler handler) {
        stream(ChatPrompt.legacy(prompt), handler);
    }

    /** 分层流式问答，与同步入口使用同一角色边界。 */
    public void stream(ChatPrompt prompt, StreamingChatResponseHandler handler) {
        Objects.requireNonNull(prompt, "prompt");
        ensureModels();
        ChatRequest request = chatRequest(prompt);
        String cacheMaterial = cacheKey(responseCacheGeneration.get() + ":" + prompt.cacheMaterial());
        count("response_cache.lookups", 1);
        String cached = cachedAnswer(cacheMaterial);
        if (cached != null) {
            count("response_cache.hit", 1);
            // 直接回放缓存，不调用模型（消费方 onCompleteResponse 不依赖 response 对象，传 null 安全）
            handler.onPartialResponse(cached);
            handler.onCompleteResponse(null);
            return;
        }
        if (primaryCircuitOpen()) {
            circuitBypassCount.incrementAndGet();
            streamWith(fallbackStreaming, fallbackModel.modelName(), request, cacheMaterial, handler, false);
            return;
        }
        streamWith(primaryStreaming, primaryModel.modelName(), request, cacheMaterial, handler, true);
    }

    private void streamWith(OpenAiStreamingChatModel model, String label, ChatRequest request,
                            String cacheMaterial,
                            StreamingChatResponseHandler handler, boolean allowFailover) {
        StringBuilder full = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean();
        count(label + ".stream", 1);
        StreamingChatResponseHandler guarded = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                synchronized (full) {
                    if (!finished.get() && partial != null) full.append(partial);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (!finished.compareAndSet(false, true)) return;
                recordTokenUsage(label, response);
                String answer = responseText(response);
                // 部分兼容供应商只在完成事件中给正文；也兼容仅发送 delta 的实现。
                if (answer == null || answer.isBlank()) {
                    synchronized (full) {
                        answer = full.toString();
                    }
                }
                if (isNonAnswerAnswer(answer)) {
                    count(label + ".non-answer", 1);
                    if (allowFailover) {
                        log.warn("{} 流式返回空泛非回答，降级重跑备用模型", label);
                        primaryFailed();
                        failover();
                        streamWith(fallbackStreaming, fallbackModel.modelName(), request,
                                cacheMaterial, handler, false);
                    } else {
                        handler.onPartialResponse(controlledNonAnswerFallback());
                        handler.onCompleteResponse(null);
                    }
                    return;
                }
                if (allowFailover) primarySucceeded();
                putCache(cacheMaterial, answer);
                handler.onPartialResponse(answer);
                handler.onCompleteResponse(response);
            }

            @Override
            public void onError(Throwable error) {
                if (!finished.compareAndSet(false, true)) return;
                // 已缓冲的部分内容尚未发送，允许备用完整重跑；每次尝试只处理一个终态。
                if (allowFailover) {
                    log.warn("{} 流式调用失败，降级重跑备用模型：{}", label, error.getMessage());
                    primaryFailed();
                    failover();
                    streamWith(fallbackStreaming, fallbackModel.modelName(), request,
                            cacheMaterial, handler, false);
                } else {
                    handler.onError(error);
                }
            }
        };
        try {
            model.chat(request, guarded);
        } catch (Exception e) {
            // SDK 可能既触发 onError 又抛异常；消费方异常也不能再次触发模型降级。
            guarded.onError(e);
        }
    }

    /**
     * 模型综合测试；不改变主备角色，也不触发自动降级。
     * 第一阶段验证最小聊天推理，第二阶段使用当前真实质量评测 Prompt 验证完整评分能力，
     * 避免“短连接测试成功、真实评测却超时或无法返回 JSON”的假阳性。
     */
    /** 取（或建）一个模型自测客户端：同一套配置复用同一实例，避免反复点"测试"累积 HttpClient。 */
    private OpenAiChatModel testClient(String baseUrl, String apiKey, String modelName,
                                       double temperature, Duration timeout) {
        String key = baseUrl + '|' + apiKey + '|' + modelName + '|' + temperature + '|' + timeout;
        OpenAiChatModel cached = testClients.get(key);
        if (cached != null) return cached;
        OpenAiChatModel built = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .temperature(temperature).timeout(timeout).maxRetries(0)
                .build();
        // 上限 8 个：超过就整体清空（下次自测会重建），把常驻实例数压住。
        if (testClients.size() > 8) testClients.clear();
        testClients.put(key, built);
        return built;
    }

    public Map<String, Object> testProvider(long id) {
        AiModelProviderService.RuntimeModel model = providers.byId(id);
        if (model.apiKey().isBlank()) throw new IllegalArgumentException("该模型尚未配置 API Key");
        String stage = "basic";
        long totalStarted = System.nanoTime();
        long basicLatencyMs = 0L;
        try {
            OpenAiChatModel basicClient = testClient(model.baseUrl(), model.apiKey(), model.modelName(),
                    0.0, Duration.ofSeconds(Math.max(5,
                            Math.min(config.getInt("model.chatTimeoutSeconds", 30), 60))));
            long basicStarted = System.nanoTime();
            String answer = basicClient.chat("这是模型基础推理测试。请只回复：连接成功");
            basicLatencyMs = elapsedMillis(basicStarted);
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("模型返回了空内容");
            }
            count(model.modelName() + ".basic-test", 1);

            stage = "quality";
            Duration judgeTimeout = Duration.ofSeconds(Math.max(5,
                    Math.min(config.getInt("quality.judgeTimeoutSeconds", 30), 120)));
            OpenAiChatModel qualityClient = testClient(model.baseUrl(), model.apiKey(), model.modelName(),
                    Math.max(0.0, Math.min(config.getDouble("quality.judgeTemperature", 0.0), 0.2)), judgeTimeout);
            long qualityStarted = System.nanoTime();
            String qualityAnswer = qualityClient.chat(qualityProbePrompt());
            long qualityLatencyMs = elapsedMillis(qualityStarted);
            String validationError = qualityProbeValidationError(qualityAnswer);
            if (validationError != null) {
                throw new IllegalStateException(validationError);
            }
            count(model.modelName() + ".quality-test", 1);

            String preview = answer == null ? "" : answer.strip();
            if (preview.length() > 120) preview = preview.substring(0, 120) + "…";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", true);
            result.put("basicPassed", true);
            result.put("qualityReady", true);
            result.put("message", "基础推理与质量评测测试均成功");
            result.put("responsePreview", preview);
            result.put("basicLatencyMs", basicLatencyMs);
            result.put("qualityLatencyMs", qualityLatencyMs);
            result.put("totalLatencyMs", elapsedMillis(totalStarted));
            result.put("judgeTimeoutSeconds", judgeTimeout.toSeconds());
            result.put("displayName", model.displayName());
            result.put("modelName", model.modelName());
            return result;
        } catch (Exception e) {
            String real = deepestMessage(e);
            if (real != null && real.length() > 200) real = real.substring(0, 200) + "…";
            log.warn("模型综合测试失败: stage={}, provider={}, model={}, basicLatencyMs={}, error={}",
                    stage, model.code(), model.modelName(), basicLatencyMs, real);
            String prefix = "quality".equals(stage)
                    ? "基础推理已通过，但质量评测测试失败"
                    : "基础推理测试失败";
            throw new IllegalArgumentException(prefix + "：" + real + "。\n"
                    + providerTestHint(stage, real));
        }
    }

    private String qualityProbePrompt() {
        String prompt = config.get("quality.judgePrompt",
                        AiConfigCatalog.defaultValue("quality.judgePrompt"))
                .replace("{storeHours}", config.get("store.businessHours",
                        AiConfigCatalog.defaultValue("store.businessHours")))
                .replace("{agentHours}", config.get("agent.workHours",
                        AiConfigCatalog.defaultValue("agent.workHours")));
        return prompt + "\n\n待复核对话：\n"
                + "[消息#9001] 客户：抗衰全脸多少钱，深圳有哪些门店可以做？\n"
                + "[消息#9002] AI客服：抗衰全脸属于抗衰项目，价格和支持门店以项目中心为准。\n";
    }

    static String qualityProbeValidationError(String output) {
        if (output == null || output.isBlank()) return "质量评测返回了空内容";
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) return "质量评测没有返回 JSON 对象";
        try {
            JsonNode node = JSON.readTree(output.substring(start, end + 1));
            for (String field : QUALITY_SCORE_FIELDS) {
                if (!node.has(field) || !node.path(field).canConvertToInt()) {
                    return "质量评测缺少整数评分字段：" + field;
                }
            }
            if (!node.path("issues").isArray()) return "质量评测字段 issues 不是数组";
            if (!node.path("suggestions").isArray()) return "质量评测字段 suggestions 不是数组";
            if (!node.path("summary").isTextual()) return "质量评测字段 summary 不是文本";
            return null;
        } catch (Exception e) {
            return "质量评测返回的 JSON 无法解析";
        }
    }

    static String providerTestHint(String stage, String error) {
        String lower = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("api key")
                || lower.contains("令牌") || lower.contains("密钥")) {
            return "API 密钥无效或已过期，请重新复制并保存密钥。";
        }
        if (lower.contains("403") || lower.contains("forbidden")) {
            return "密钥没有调用该模型的权限，请检查模型授权范围。";
        }
        if (lower.contains("404") || lower.contains("model not found")) {
            return "模型标识或 API Base URL 不正确，请按供应商 OpenAI 兼容文档核对。";
        }
        if (lower.contains("429") || lower.contains("rate limit")
                || lower.contains("too many requests") || lower.contains("限流")) {
            return "模型当前被限流或额度不足，请检查额度并稍后重试。";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")) {
            return "quality".equals(stage)
                    ? "模型可以连接，但未在“评测超时”配置内完成真实评测；请检查模型负载、冷启动或适当调高评测超时。"
                    : "模型未在聊天超时配置内完成基础推理，请检查模型服务状态、负载或网络链路。";
        }
        if (lower.contains("permission denied") || lower.contains("connectexception")
                || lower.contains("connection") || lower.contains("unknown host")
                || lower.contains("dns")) {
            return "服务进程无法连接模型地址，请检查服务器出网权限、DNS、防火墙和 API 地址。";
        }
        if ("quality".equals(stage)) {
            return "模型连接正常，但不能稳定完成当前质量评测 Prompt 或 JSON 输出要求；不应将它标记为评测可用。";
        }
        return "请检查 API 地址、模型标识、密钥、额度及接口是否兼容 OpenAI 协议。";
    }

    private static String deepestMessage(Throwable error) {
        String result = null;
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                result = current.getMessage();
            }
            current = current.getCause();
        }
        return result == null ? error.getClass().getSimpleName() : result;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    // ==================== Embedding（无主备，知识库向量固定模型） ====================

    /** 单条文本向量化 */
    public float[] embed(String text) {
        ensureModels();
        Embedding e = embeddingModel.embed(text).content();
        count("embedding." + setting("model.embedding.name"), 1);
        return e.vector();
    }

    /**
     * 批量向量化（调用方自行分批，通义单次最多 10 条）。
     * 注意：返回的 float[] 顺序与入参一一对应。
     */
    public List<float[]> embedAll(List<String> texts) {
        ensureModels();
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        count("embedding." + setting("model.embedding.name"), texts.size());
        return embeddings.stream().map(Embedding::vector).toList();
    }

    @FunctionalInterface
    public interface EmbeddingBatchClient {
        List<float[]> embedAll(List<String> texts);
    }

    /**
     * 创建不影响当前线上模型的候选向量客户端。安全切换任务用它先完成全量重建，
     * 只有重建成功后才发布配置，避免查询向量提前进入新的语义空间。
     */
    public EmbeddingBatchClient candidateEmbeddingClient(String baseUrl, String modelName, String apiKey) {
        Duration timeout = Duration.ofSeconds(config.getInt("model.streamTimeoutSeconds", 60));
        OpenAiEmbeddingModel candidate = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .build();
        return texts -> {
            if (texts == null || texts.isEmpty()) return List.of();
            List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
            List<Embedding> embeddings = candidate.embedAll(segments).content();
            count("embedding.candidate." + modelName, texts.size());
            return embeddings.stream().map(Embedding::vector).toList();
        };
    }

    // ==================== 视觉模型（OCR 识图） ====================

    /** 图片/多模态问答（qwen-vl-max）：OCR 识图用，失败抛异常由调用方兜底 */
    public String visionChat(UserMessage message) {
        ensureModels();
        ChatResponse visionResp = visionModel.chat(message);
        recordTokenUsage(setting("model.vision.name"), visionResp);
        String answer = visionResp.aiMessage().text();
        count(setting("model.vision.name") + ".vision", 1);
        // 模型无文本输出时 aiMessage().text() 为 null；统一归一化为空串，
        // 避免调用方 text.isBlank() 抛 NPE 掩盖成 500（本意是"未解析出文字"的友好提示）。
        return answer == null ? "" : answer;
    }

    // ==================== 指标 ====================

    /**
     * 记录一次模型调用的 token 用量（按实际模型名累计）。
     * 单价随供应商调价变动，代码里不写死价格——分模型的 token 数是稳定口径，
     * 成本 = 各模型 token × 当时单价，可由外部按需计算。
     */
    private void recordTokenUsage(String modelName, ChatResponse response) {
        if (response == null || response.tokenUsage() == null) return;
        TokenUsage usage = response.tokenUsage();
        Integer in = usage.inputTokenCount();
        Integer out = usage.outputTokenCount();
        Integer total = usage.totalTokenCount();
        if (in != null && in > 0) count("token.input." + modelName, in);
        if (out != null && out > 0) count("token.output." + modelName, out);
        if (total != null && total > 0) count("token.total." + modelName, total);
    }

    /** 供其他组件（检索/旁路/精排）把非模型调用的可观测性计数写进同一指标口径。 */
    public void counter(String name, long n) {
        count(name, n);
    }

    /** 指标快照：模型调用次数、降级次数、最近降级时间（供 /api/metrics 与管理后台） */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        callCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> m.put(e.getKey(), e.getValue().get()));
        m.put("failover_count", failoverCount.get());
        m.put("last_failover_at", lastFailoverAt == 0 ? null
                : new java.util.Date(lastFailoverAt).toString());
        m.put("primary_consecutive_failures", primaryConsecutiveFailures.get());
        m.put("primary_circuit_open", primaryCircuitOpen());
        m.put("primary_circuit_open_until", primaryCircuitOpenUntil == 0 ? null
                : new java.util.Date(primaryCircuitOpenUntil).toString());
        m.put("circuit_bypass_count", circuitBypassCount.get());
        long cacheHit = callCounts.getOrDefault("response_cache.hit", new AtomicLong()).get();
        long cacheLookups = callCounts.getOrDefault("response_cache.lookups", new AtomicLong()).get();
        double cacheRate = cacheLookups > 0 ? (cacheHit * 100.0 / cacheLookups) : 0.0;
        m.put("response_cache.hit_rate", Math.round(cacheRate * 10.0) / 10.0);
        // token 用量：按模型细分已包含在上面的 callCounts 里，这里再给三个总量，便于一眼看成本盘子
        long tokenIn = 0, tokenOut = 0, tokenTotal = 0;
        for (Map.Entry<String, AtomicLong> e : callCounts.entrySet()) {
            if (e.getKey().startsWith("token.input.")) tokenIn += e.getValue().get();
            else if (e.getKey().startsWith("token.output.")) tokenOut += e.getValue().get();
            else if (e.getKey().startsWith("token.total.")) tokenTotal += e.getValue().get();
        }
        m.put("token_input", tokenIn);
        m.put("token_output", tokenOut);
        m.put("token_total", tokenTotal);
        // 趋势时间序列：固定窗口 TREND_WINDOW_BUCKETS 桶（最近 3 小时）。
        // 始终输出完整窗口，无记录的分钟补 0，保证前端即便一条调用都没有也能画出坐标轴。
        long nowBucket = (System.currentTimeMillis() / TREND_BUCKET_MS) * TREND_BUCKET_MS;
        List<Map<String, Object>> ts = new ArrayList<>();
        for (int i = TREND_WINDOW_BUCKETS - 1; i >= 0; i--) {
            long start = nowBucket - i * TREND_BUCKET_MS;
            TrendBucket bk = trendBuckets.get(start);
            long hit = bk == null ? 0 : bk.cacheHit.get();
            long lookups = bk == null ? 0 : bk.cacheLookups.get();
            long miss = Math.max(0, lookups - hit);
            long totalCalls = 0;
            long failover = bk == null ? 0 : bk.failover.get();
            Map<String, Long> byModel = new LinkedHashMap<>();
            if (bk != null) {
                for (Map.Entry<String, AtomicLong> ce : bk.calls.entrySet()) {
                    String k = ce.getKey();
                    long cnt = ce.getValue().get();
                    totalCalls += cnt;
                    int idx = k.lastIndexOf('.');
                    String suffix = idx >= 0 ? k.substring(idx + 1) : "";
                    String model = idx >= 0 ? k.substring(0, idx) : k;
                    if (suffix.equals("chat") || suffix.equals("stream")
                            || suffix.equals("quality-judge") || suffix.equals("vision")) {
                        byModel.put(model, byModel.getOrDefault(model, 0L) + cnt);
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", start);
            row.put("cacheHit", hit);
            row.put("cacheLookups", lookups);
            row.put("cacheMiss", miss);
            row.put("failover", failover);
            row.put("calls", totalCalls);
            row.put("byModel", byModel);
            ts.add(row);
        }
        m.put("timeseries", ts);
        return m;
    }

    private void failover() {
        failoverCount.incrementAndGet();
        lastFailoverAt = System.currentTimeMillis();
        currentTrendBucket().failover.incrementAndGet();
    }

    private void count(String name, long n) {
        AtomicLong counter = callCounts.computeIfAbsent(name, k -> new AtomicLong());
        counter.addAndGet(n);
        registerPrometheusCounter(name, counter);
        TrendBucket b = currentTrendBucket();
        if ("response_cache.hit".equals(name)) b.cacheHit.addAndGet(n);
        else if ("response_cache.lookups".equals(name)) b.cacheLookups.addAndGet(n);
        else b.calls.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(n);
    }

    private boolean primaryCircuitOpen() {
        long until = primaryCircuitOpenUntil;
        if (until == 0) return false;
        if (System.currentTimeMillis() < until) return true;
        primaryCircuitOpenUntil = 0; // 冷却完成，下一次请求试探主模型
        return false;
    }

    private void primarySucceeded() {
        primaryConsecutiveFailures.set(0);
        primaryCircuitOpenUntil = 0;
    }

    private void primaryFailed() {
        int failures = primaryConsecutiveFailures.incrementAndGet();
        int threshold = Math.max(1, config.getInt("model.circuitFailureThreshold", 3));
        if (failures >= threshold) {
            long cooldownMillis = Math.max(10, config.getInt("model.circuitCooldownSeconds", 120)) * 1000L;
            primaryCircuitOpenUntil = System.currentTimeMillis() + cooldownMillis;
            log.warn("主模型连续失败 {} 次，熔断 {} 秒，期间直接使用备用模型", failures, cooldownMillis / 1000L);
        }
    }
}
