package com.example.aics.chat;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A chat prompt split by trust boundary.
 *
 * <p>{@code system} contains trusted behavior and safety rules, {@code context}
 * contains untrusted conversation/business data, and {@code user} contains only
 * the current customer request (or the history-resolved form of that request).
 * Keeping the layers separate prevents retrieved documents and customer text
 * from being promoted to system instructions.</p>
 */
public record ChatPrompt(
        String system,
        String context,
        String user,
        List<PromptModule.Descriptor> modules,
        String version
) {

    public ChatPrompt {
        system = normalize(system);
        context = normalize(context);
        user = normalize(user);
        modules = modules == null ? List.of() : List.copyOf(modules);
        version = version == null || version.isBlank() ? "direct-v1" : version.strip();
    }

    /** 保留原三层构造方式；新业务代码应优先使用 {@link PromptAssembler}。 */
    public ChatPrompt(String system, String context, String user) {
        this(system, context, user, defaultDescriptors(system, context, user), "direct-v1");
    }

    /** Compatibility wrapper for old callers that only have one user prompt. */
    public static ChatPrompt legacy(String prompt) {
        return new ChatPrompt("", "", prompt, descriptorsFor(
                new PromptModule("legacy.user", "v1", PromptModule.Layer.USER, 100, prompt)),
                "legacy-v1");
    }

    /** Add a trusted channel-specific rule without modifying the untrusted context. */
    public ChatPrompt prependSystem(String trustedRule) {
        String rule = normalize(trustedRule);
        if (rule.isBlank()) return this;
        List<PromptModule.Descriptor> updatedModules = new ArrayList<>();
        updatedModules.add(new PromptModule("channel.scope", "v1", PromptModule.Layer.SYSTEM, 0, rule)
                .descriptor());
        updatedModules.addAll(modules);
        return new ChatPrompt(rule + (system.isBlank() ? "" : "\n\n" + system), context, user,
                updatedModules, version);
    }

    /**
     * Text carried by the user-role message. Context and the current request stay
     * visibly separated even for providers that only support system/user roles.
     */
    public String requestText() {
        if (context.isBlank()) return user;
        return """
                <context>
                %s
                </context>

                <user_message>
                %s
                </user_message>
                """.formatted(context, user).trim();
    }

    /** Human-readable debug representation; use cacheMaterial() for identity. */
    public String debugText() {
        StringBuilder out = new StringBuilder(4096);
        append(out, "SYSTEM", system);
        append(out, "CONTEXT", context);
        append(out, "USER", user);
        return out.toString();
    }

    /** 长度前缀使正文中的角色标签、换行和分隔符无法伪造另一份 Prompt 的边界。 */
    public String cacheMaterial() {
        StringBuilder out = new StringBuilder();
        for (String value : List.of(version, system, context, user)) {
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    /** Debug API representation; no model-specific message classes leak upward. */
    public Map<String, String> layers() {
        Map<String, String> layers = new LinkedHashMap<>();
        layers.put("system", system);
        layers.put("context", context);
        layers.put("user", user);
        return layers;
    }

    /** 不包含 Prompt 正文，可安全写入调试结果和决策追踪。 */
    public Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("fingerprint", fingerprint());
        result.put("modules", modules.stream().map(PromptModule.Descriptor::toMap).toList());
        result.put("systemChars", system.length());
        result.put("contextChars", context.length());
        result.put("userChars", user.length());
        result.put("totalChars", debugText().length());
        return result;
    }

    /** 配置内容或启用模块变化时都会变化，用于定位两轮回答是否使用了同一份 Prompt。 */
    public String fingerprint() {
        return PromptModule.shortHash(cacheMaterial());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<PromptModule.Descriptor> defaultDescriptors(String system, String context, String user) {
        List<PromptModule.Descriptor> result = new ArrayList<>();
        addDescriptor(result, "direct.system", PromptModule.Layer.SYSTEM, system);
        addDescriptor(result, "direct.context", PromptModule.Layer.CONTEXT, context);
        addDescriptor(result, "direct.user", PromptModule.Layer.USER, user);
        return List.copyOf(result);
    }

    private static void addDescriptor(List<PromptModule.Descriptor> target, String id,
                                      PromptModule.Layer layer, String content) {
        PromptModule module = new PromptModule(id, "v1", layer, 100, content);
        if (module.active()) target.add(module.descriptor());
    }

    private static List<PromptModule.Descriptor> descriptorsFor(PromptModule module) {
        return module.active() ? List.of(module.descriptor()) : List.of();
    }

    private static void append(StringBuilder out, String role, String value) {
        if (value.isBlank()) return;
        if (!out.isEmpty()) out.append("\n\n");
        out.append('[').append(role).append("]\n").append(value);
    }
}
