package com.example.aics.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次对话路由的可审计摘要，不包含用户原文和 Prompt。 */
public record TurnTrace(
        String route,
        String routeReason,
        String authorityDomain,
        String authoritySource,
        boolean knowledgeFallbackAllowed,
        boolean modelRequired,
        int candidateCount,
        int relevantCount,
        List<String> sourceRefs,
        long routingDurationMs,
        String promptVersion,
        String promptHash,
        List<PromptModule.Descriptor> promptModules,
        String knowledgeStatus
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public TurnTrace {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        promptModules = promptModules == null ? List.of() : List.copyOf(promptModules);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", route);
        result.put("routeReason", routeReason);
        result.put("authorityDomain", authorityDomain);
        result.put("authoritySource", authoritySource);
        result.put("knowledgeFallbackAllowed", knowledgeFallbackAllowed);
        result.put("modelRequired", modelRequired);
        result.put("candidateCount", candidateCount);
        result.put("relevantCount", relevantCount);
        result.put("sourceRefs", sourceRefs);
        result.put("routingDurationMs", routingDurationMs);
        result.put("knowledgeStatus", knowledgeStatus);
        result.put("promptVersion", promptVersion);
        result.put("promptHash", promptHash);
        result.put("promptModules", promptModules.stream()
                .map(PromptModule.Descriptor::toMap)
                .toList());
        return result;
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(toMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化对话路由追踪失败", e);
        }
    }
}
