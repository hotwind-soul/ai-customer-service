package com.example.aics.knowledge;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.ConfigService;
import com.example.aics.config.KeyConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索精排（Rerank）：混合检索（向量 + 关键词 RRF）给出的是"相关性排序"，
 * 但 RRF 只看排名位次、不理解语义；精排模型（通义 gte-rerank）对 query 与候选逐对打分，
 * 把真正回答问题的候选顶到前面——它直接决定 topK 的质量，是检索链路里性价比最高的升级点。
 *
 * 设计原则：
 *  - 默认关闭（retrieval.rerankEnabled=false），需要配置通义 Key 后显式开启；
 *  - 任何失败（未配 key / 网络异常 / 响应格式变化）一律回退到精排前的顺序，绝不影响检索可用性；
 *  - 只对头部窗口（topK 的 3 倍、至少 10 条）精排，控制调用成本与延迟。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);
    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final KeyConfigService keys;
    private final ConfigService config;
    private final ModelGateway modelGateway;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public RerankService(KeyConfigService keys, ConfigService config, ModelGateway modelGateway) {
        this.keys = keys;
        this.config = config;
        this.modelGateway = modelGateway;
    }

    private boolean enabled() {
        return config.getBoolean("retrieval.rerankEnabled", false) && !apiKey().isBlank();
    }

    private String apiKey() {
        return keys.get("ai.dashscope_key", "DASHSCOPE_API_KEY");
    }

    /**
     * 对已排序候选做精排，返回重排后的列表（精排窗口之外的候选按原顺序补齐在尾部）。
     * 未启用 / 候选不足 / 调用失败时原样返回，调用方无需判空。
     */
    public List<VectorStore.Match> apply(String query, List<VectorStore.Match> candidates, int topK) {
        if (candidates == null || candidates.size() < 2) return candidates;
        if (!enabled()) return candidates;
        int window = Math.min(candidates.size(), Math.max(topK * 3, 10));
        List<VectorStore.Match> head = candidates.subList(0, window);
        int[] order = rerank(query, head);
        if (order == null) {
            modelGateway.counter("retrieval.rerank.fail", 1);
            return candidates;
        }
        modelGateway.counter("retrieval.rerank.ok", 1);
        List<VectorStore.Match> out = new ArrayList<>(candidates.size());
        for (int idx : order) {
            if (idx >= 0 && idx < head.size()) out.add(head.get(idx));
        }
        for (VectorStore.Match m : candidates) {
            if (!out.contains(m)) out.add(m);   // 精排窗口之外的按原顺序补齐
        }
        return out;
    }

    /** 调用精排接口，返回"应展示的候选下标顺序"；失败返回 null（调用方回退原序）。 */
    private int[] rerank(String query, List<VectorStore.Match> head) {
        try {
            var body = json.createObjectNode();
            body.put("model", config.get("retrieval.rerankModel", "gte-rerank"));
            var input = body.putObject("input");
            input.put("query", query == null ? "" : query);
            var docs = input.putArray("documents");
            for (VectorStore.Match m : head) docs.add(m.content() == null ? "" : m.content());
            body.putObject("parameters").put("return_documents", false)
                    .put("top_n", Math.min(head.size(), 20));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.get("retrieval.rerankEndpoint", DEFAULT_ENDPOINT)))
                    .timeout(Duration.ofMillis(Math.max(500, config.getInt("retrieval.rerankTimeoutMs", 3000))))
                    .header("Authorization", "Bearer " + apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("精排接口返回 {}：{}", resp.statusCode(),
                        resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                return null;
            }
            JsonNode results = json.readTree(resp.body()).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) return null;
            int[] order = new int[results.size()];
            for (int i = 0; i < results.size(); i++) order[i] = results.get(i).path("index").asInt(-1);
            return order;
        } catch (Exception e) {
            log.warn("精排调用失败，回退原序：{}", e.getMessage());
            return null;
        }
    }
}
