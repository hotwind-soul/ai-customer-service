package com.example.aics.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/** 按信任层和显式顺序组装 Prompt，并保留实际启用模块的审计信息。 */
public final class PromptAssembler {
    private final String version;
    private final Map<String, Integer> moduleBudgets;
    private final List<PromptModule> modules = new ArrayList<>();
    private final Set<String> moduleIds = new HashSet<>();

    public PromptAssembler(String version) {
        this(version, Map.of());
    }

    public PromptAssembler(String version, Map<String, Integer> moduleBudgets) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Prompt 版本不能为空");
        this.version = version.strip();
        this.moduleBudgets = moduleBudgets == null ? Map.of() : Map.copyOf(moduleBudgets);
    }

    public PromptAssembler add(String id, String moduleVersion, PromptModule.Layer layer,
                               int order, String content) {
        PromptModule module = new PromptModule(id, moduleVersion, layer, order, content);
        Integer budget = moduleBudgets.get(module.id());
        if (budget != null) module = module.bounded(budget, truncationFor(module.id()));
        if (!module.active()) return this;
        if (!moduleIds.add(module.id())) {
            throw new IllegalArgumentException("Prompt 模块 id 重复：" + module.id());
        }
        modules.add(module);
        return this;
    }

    private PromptModule.Truncation truncationFor(String id) {
        if ("memory.recent-history".equals(id)) return PromptModule.Truncation.TAIL;
        if ("request.current".equals(id)) return PromptModule.Truncation.HEAD_TAIL;
        return PromptModule.Truncation.HEAD;
    }

    public ChatPrompt assemble() {
        List<PromptModule> active = modules.stream()
                .sorted(Comparator.comparing(PromptModule::layer)
                        .thenComparingInt(PromptModule::order)
                        .thenComparing(PromptModule::id))
                .toList();
        String system = join(active, PromptModule.Layer.SYSTEM);
        String context = join(active, PromptModule.Layer.CONTEXT);
        String user = join(active, PromptModule.Layer.USER);
        List<PromptModule.Descriptor> descriptors = active.stream()
                .map(PromptModule::descriptor)
                .toList();
        return new ChatPrompt(system, context, user, descriptors, version);
    }

    private String join(List<PromptModule> source, PromptModule.Layer layer) {
        return source.stream()
                .filter(module -> module.layer() == layer)
                .map(PromptModule::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }
}
