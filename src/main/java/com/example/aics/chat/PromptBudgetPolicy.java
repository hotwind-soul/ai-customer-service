package com.example.aics.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 解析并约束 Prompt 模块字符预算；字符数比供应商相关的 token 估算更稳定。 */
public final class PromptBudgetPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MIN_BUDGET = 200;
    private static final int MAX_BUDGET = 50_000;
    private static final Map<String, Integer> SAFETY_MINIMUMS = Map.ofEntries(
            Map.entry("identity.agent", 500),
            Map.entry("safety.forbidden-words", 500),
            Map.entry("safety.prompt-injection", 500),
            Map.entry("scenario.rag-answer", 800),
            Map.entry("policy.rag-answer", 1000),
            Map.entry("safety.medical", 800),
            Map.entry("style.customer", 500),
            Map.entry("memory.customer-profile", 500),
            Map.entry("understanding.resolved-question", 500),
            Map.entry("memory.recent-history", 1000),
            Map.entry("knowledge.retrieved", 2000),
            Map.entry("request.current", 1000));

    private PromptBudgetPolicy() {}

    public static Map<String, Integer> parse(String json, String defaultJson) {
        Map<String, Integer> fallback = parseObject(defaultJson, Map.of());
        return parseObject(json, fallback);
    }

    private static Map<String, Integer> parseObject(String json, Map<String, Integer> fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            Map<String, Object> raw = JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, Integer> result = new LinkedHashMap<>();
            raw.forEach((id, value) -> {
                if (id == null || id.isBlank()) return;
                int budget = Integer.parseInt(String.valueOf(value));
                int minimum = minimumFor(id);
                if (budget < minimum || budget > MAX_BUDGET) {
                    throw new IllegalArgumentException("模块 " + id + " 的字符预算必须在 "
                            + minimum + " 到 " + MAX_BUDGET + " 之间");
                }
                result.put(id.strip(), budget);
            });
            return Map.copyOf(result);
        } catch (RuntimeException e) {
            if (!fallback.isEmpty()) return fallback;
            throw new IllegalArgumentException("Prompt 模块预算必须是模块 ID 到整数的 JSON 对象", e);
        } catch (Exception e) {
            if (!fallback.isEmpty()) return fallback;
            throw new IllegalArgumentException("Prompt 模块预算必须是模块 ID 到整数的 JSON 对象", e);
        }
    }

    public static int minimumFor(String moduleId) {
        return SAFETY_MINIMUMS.getOrDefault(moduleId, MIN_BUDGET);
    }
}
