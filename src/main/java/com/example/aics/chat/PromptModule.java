package com.example.aics.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个可独立追踪的 Prompt 模块。
 *
 * <p>模块只负责声明内容属于哪个信任层以及在该层内的顺序；真正的拼装由
 * {@link PromptAssembler} 完成。调试和持久化只暴露 {@link Descriptor}，不保存
 * 模块正文，避免把客户画像、历史或知识内容复制进决策追踪。</p>
 */
public record PromptModule(
        String id,
        String version,
        Layer layer,
        int order,
        String content,
        int originalCharCount,
        Integer charBudget
) {
    public enum Truncation {
        HEAD,
        TAIL,
        HEAD_TAIL
    }

    public enum Layer {
        SYSTEM,
        CONTEXT,
        USER
    }

    public PromptModule(String id, String version, Layer layer, int order, String content) {
        this(id, version, layer, order, normalize(content), normalize(content).length(), null);
    }

    public PromptModule {
        id = requireText(id, "模块 id");
        version = requireText(version, "模块版本");
        if (layer == null) throw new IllegalArgumentException("模块层级不能为空");
        content = normalize(content);
        if (originalCharCount < content.length()) originalCharCount = content.length();
        if (charBudget != null && charBudget < 1) throw new IllegalArgumentException("模块字符预算必须大于 0");
    }

    public boolean active() {
        return !content.isBlank();
    }

    public Descriptor descriptor() {
        return new Descriptor(id, version, layer.name().toLowerCase(java.util.Locale.ROOT),
                order, content.length(), originalCharCount, charBudget,
                originalCharCount > content.length(), shortHash(content));
    }

    /** 超出预算时只裁剪当前模块；不会把低信任内容提升到其他层。 */
    public PromptModule bounded(int maxChars, Truncation truncation) {
        if (maxChars < 1) throw new IllegalArgumentException("模块字符预算必须大于 0");
        if (content.length() <= maxChars) {
            return new PromptModule(id, version, layer, order, content, originalCharCount, maxChars);
        }
        Truncation mode = truncation == null ? Truncation.HEAD : truncation;
        String marker = "\n…[内容已按预算裁剪]…\n";
        String bounded;
        if (maxChars <= marker.length() + 2) {
            bounded = safePrefix(content, maxChars);
        } else if (mode == Truncation.TAIL) {
            bounded = marker + safeSuffix(content, maxChars - marker.length());
        } else if (mode == Truncation.HEAD_TAIL) {
            int available = maxChars - marker.length();
            int head = (available + 1) / 2;
            bounded = safePrefix(content, head) + marker
                    + safeSuffix(content, available - head);
        } else {
            bounded = safePrefix(content, maxChars - marker.length()) + marker;
        }
        return new PromptModule(id, version, layer, order, bounded, originalCharCount, maxChars);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String safePrefix(String value, int length) {
        int end = Math.max(0, Math.min(length, value.length()));
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static String safeSuffix(String value, int length) {
        int start = Math.max(0, value.length() - Math.max(0, length));
        if (start > 0 && start < value.length() && Character.isLowSurrogate(value.charAt(start))) start++;
        return value.substring(start);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }

    static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /** 不含正文的模块审计信息。 */
    public record Descriptor(
            String id,
            String version,
            String layer,
            int order,
            int charCount,
            int originalCharCount,
            Integer charBudget,
            boolean truncated,
            String contentHash
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("version", version);
            result.put("layer", layer);
            result.put("order", order);
            result.put("charCount", charCount);
            result.put("originalCharCount", originalCharCount);
            result.put("charBudget", charBudget);
            result.put("truncated", truncated);
            result.put("contentHash", contentHash);
            return result;
        }
    }
}
