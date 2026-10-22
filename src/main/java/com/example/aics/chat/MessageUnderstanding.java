package com.example.aics.chat;

import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.ConfigService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量语义理解：在进入向量检索前识别项目、咨询维度和问法完整度。
 *
 * <p>这里只做高确定性的本地判断，不负责回答业务知识。这样既能避免把“淡斑”
 * 当成普通两个字拦截，也能让“淡斑 → 多少钱”这类多轮追问使用完整问题检索。</p>
 */
public final class MessageUnderstanding {

    private static final List<ProjectRule> PROJECTS = List.of(
            new ProjectRule("抗衰", List.of("面部提升", "提拉紧致", "紧致提升", "抗衰", "抗老")),
            new ProjectRule("淡斑", List.of("黄褐斑", "雀斑", "色斑", "祛斑", "淡斑")),
            new ProjectRule("眼袋", List.of("去眼袋", "眼袋")),
            new ProjectRule("胡子", List.of("脱胡须", "脱胡子", "胡须", "胡子"))
    );

    private static final List<String> GREETINGS = List.of(
            "你好", "您好", "在吗", "有人吗", "哈喽", "嗨", "hello", "hi");
    private static final List<String> GENERIC_VAGUE = List.of(
            "咨询", "咨询一下", "我想咨询", "想了解", "了解一下", "问一下", "我问问",
            "帮帮我", "这个", "那个", "怎么弄", "怎么办");
    private static final List<String> PROJECT_ONLY_FILLERS = List.of(
            "我想了解一下", "我想咨询一下", "想咨询一下", "想了解一下", "咨询一下", "了解一下",
            "我想了解", "我想咨询", "想了解", "想咨询", "我想做", "想做", "我要做", "请问",
            "咨询", "了解", "项目", "这个", "一下", "可以吗", "行吗", "呢", "吗", "的", "呀", "啊", "哦");

    private MessageUnderstanding() {}

    public record Result(
            String project,
            String aspect,
            boolean complete,
            boolean inheritedProject,
            String retrievalQuery,
            String reason,
            String nextQuestion
    ) {
        public static Result notAnalyzed() {
            return new Result(null, null, true, false, "", "未分析", null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("project", project);
            result.put("aspect", aspect);
            result.put("complete", complete);
            result.put("inheritedProject", inheritedProject);
            result.put("retrievalQuery", retrievalQuery);
            result.put("reason", reason);
            result.put("nextQuestion", nextQuestion);
            return result;
        }
    }

    private record ProjectRule(String canonical, List<String> aliases) {}

    public static Result analyze(String message, String history) {
        return analyze(message, history, null);
    }

    /** 正式聊天链路使用的热配置版本；保留双参数方法供纯函数测试和离线工具使用。 */
    public static Result analyze(String message, String history, ConfigService config) {
        return analyze(message, history, config, null);
    }

    /** 项目中心版本：项目标准名和别名直接来自结构化目录。 */
    public static Result analyze(String message, String history, ConfigService config,
                                 Map<String, List<String>> catalogRules) {
        String raw = message == null ? "" : message.trim();
        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            return incomplete(null, null, "消息为空", genericQuestion(config));
        }

        List<ProjectRule> projects = projectRules(config, catalogRules);
        Map<String, List<String>> aspects = config == null ? defaultAspects()
                : AiConfigCatalog.stringListMap(config, "intent.aspectRules");
        String currentProject = detectProject(raw, projects);
        String aspect = detectAspect(raw, aspects);
        // 兼容运营配置尚未补齐的口语表达，避免“能管多久/保持多久”漏掉效果维度。
        if (aspect == null && containsAny(normalized, "能管多久", "管多久", "保持多久", "撑多久")) {
            aspect = "效果";
        }
        String historyProject = currentProject == null ? detectRecentUserProject(history, projects) : null;

        if (isGreeting(normalized, config)) {
            return incomplete(null, null, "问候语未包含咨询内容", "您好呀～" + genericQuestion(config));
        }
        List<String> vague = config == null ? GENERIC_VAGUE : AiConfigCatalog.keywords(config, "intent.genericVague");
        if (vague.stream().map(MessageUnderstanding::normalize).anyMatch(normalized::equals)) {
            return incomplete(null, null, "未识别到项目或具体问题", genericQuestion(config));
        }

        // “淡斑”“我想咨询淡斑”等已经说明项目，但没有说明想问什么，应围绕该项目定向追问。
        if (currentProject != null && aspect == null && isProjectOnly(normalized, projects, config)) {
            return incomplete(currentProject, null, "已识别项目，缺少咨询维度", projectQuestion(currentProject, config));
        }

        // 多轮补全：“淡斑”之后再问“多少钱/效果呢/要做几次”，使用上一轮项目改写检索问题。
        // 兜底放宽：aspect 依赖运营可改的 intent.aspectRules，一旦该配置被删词或写窄，
        // 追问句会识别不出维度。只要本轮明显在追问项目属性（价格/恢复/效果等），
        // 就仍然继承历史项目，避免检索丢掉项目过滤、把 A 项目的价格报成 B 项目。
        if (currentProject == null && historyProject != null
                && (aspect != null || mentionsProjectProperty(raw))) {
            String inheritedAspect = aspect != null ? aspect : inferAspect(raw);
            return new Result(historyProject, inheritedAspect, true, true,
                    historyProject + "，" + raw, "从最近一轮客户消息继承项目", null);
        }

        // 很短的维度问法，以及恢复/流程这类答案会随项目明显变化的问法，必须先确定项目。
        // 不能让“做完能上班吗”在项目未知时混检不同项目并生成看似确定的恢复承诺。
        int shortMax = config == null ? 5 : config.getInt("intent.shortAspectMaxLength", 5);
        boolean projectDependentProcess = "流程".equals(aspect);
        boolean projectDependentRecovery = "恢复".equals(aspect);
        // “能维持多久/能管多久”高度依赖具体项目。没有项目时直接检索会把不同项目的
        // 效果资料混在一起，既答非所问，也容易生成没有依据的固定时长承诺。
        boolean projectDependentDuration = "效果".equals(aspect)
                && containsAny(normalized, "维持多久", "能管多久", "管多久", "保持多久", "撑多久");
        // 价格高度依赖具体项目：四个项目价格互不相同，没有项目时进检索会让模型“挑一个项目”报价
        // （实测答成胡子 272 元，甚至编出订金 28 元）。因此价格类问句不受 shortMax 长度限制，
        // 只要没有项目（本轮没说、历史也没有）就一律先反问；aspect 未识别时用本地词表兜底判定。
        String resolvedAspect = aspect != null ? aspect : inferAspect(raw);
        boolean projectDependentPrice = "价格".equals(resolvedAspect);
        boolean projectDependentOperationDuration = containsAny(normalized,
                "做一次项目要多久", "做一次要多久", "项目要多久", "操作要多久", "操作多长时间", "项目多长时间");
        if (currentProject == null && historyProject == null && projectDependentOperationDuration) {
            return incomplete(null, "流程", "操作时长依赖具体项目，缺少项目",
                    "您想问哪个项目的操作时长呢？告诉我项目名称，我再帮您查准确资料。");
        }
        if (currentProject == null && historyProject == null
                && (projectDependentPrice
                    || (aspect != null && (normalized.length() <= shortMax
                        || projectDependentProcess || projectDependentRecovery || projectDependentDuration)))) {
            String askAspect = projectDependentPrice ? resolvedAspect : aspect;
            return incomplete(null, askAspect, "该咨询维度依赖具体项目，缺少项目",
                    aspectQuestion(askAspect, config));
        }

        return new Result(currentProject, aspect, true, false, raw, "信息足够，可进入检索", null);
    }

    private static Result incomplete(String project, String aspect, String reason, String question) {
        return new Result(project, aspect, false, false, "", reason, question);
    }

    private static boolean isGreeting(String normalized, ConfigService config) {
        List<String> greetings = config == null ? GREETINGS : AiConfigCatalog.keywords(config, "intent.greetings");
        return greetings.stream().map(MessageUnderstanding::normalize).anyMatch(normalized::equals);
    }

    private static boolean isProjectOnly(String normalized, List<ProjectRule> projects, ConfigService config) {
        String rest = normalized;
        for (ProjectRule project : projects) {
            for (String alias : project.aliases()) {
                rest = rest.replace(normalize(alias), "");
            }
        }
        List<String> fillers = config == null ? PROJECT_ONLY_FILLERS
                : AiConfigCatalog.keywords(config, "intent.projectFillers");
        for (String filler : fillers) {
            rest = rest.replace(normalize(filler), "");
        }
        return rest.isBlank();
    }

    private static String detectProject(String text, List<ProjectRule> projects) {
        if (text == null || text.isBlank()) return null;
        String normalized = normalize(text);
        String matched = null;
        int longest = 0;
        int earliest = Integer.MAX_VALUE;
        for (ProjectRule project : projects) {
            for (String alias : project.aliases()) {
                String candidate = normalize(alias);
                if (candidate.isBlank()) continue;
                int position = normalized.indexOf(candidate);
                // 子项目/具体方案优先，避免目录顺序使“抗衰全脸”退化为“抗衰”。
                if (position >= 0 && (candidate.length() > longest
                        || (candidate.length() == longest && position < earliest))) {
                    matched = project.canonical();
                    longest = candidate.length();
                    earliest = position;
                }
            }
        }
        return matched;
    }

    private static String detectRecentUserProject(String history, List<ProjectRule> projects) {
        if (history == null || history.isBlank() || history.contains("无历史对话")) return null;
        String[] lines = history.split("\\R");
        boolean foundRoleLine = false;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.startsWith("用户：") || line.startsWith("用户:")
                    || line.startsWith("客户：") || line.startsWith("客户:")
                    || lower.startsWith("user:") || lower.startsWith("user：")) {
                foundRoleLine = true;
                String project = detectProject(line, projects);
                if (project != null) return project;
            } else if (line.startsWith("客服：") || line.startsWith("客服:")
                    || line.startsWith("助手：") || line.startsWith("助手:")
                    || line.startsWith("客服开场语：") || line.startsWith("客服开场语:")
                    || lower.startsWith("assistant:") || lower.startsWith("assistant：")
                    || lower.startsWith("system:") || lower.startsWith("system：")) {
                foundRoleLine = true;
            }
        }
        // 管理端允许粘贴不带角色前缀的历史；仅在完全没有角色标签时兜底识别。
        return foundRoleLine ? null : detectProject(history, projects);
    }

    // 不依赖线上 intent.aspectRules 的“本地兜底”维度词表，有两个用途：
    //  1) 多轮追问时若 aspect 未识别，仍能继承上一轮项目（见 analyze 的继承分支），避免检索串项目；
    //  2) 判定「价格」这类项目专属维度——没有项目时必须先反问，绝不能进检索让模型自己挑项目。
    // 实测依据（2026-08-15，直连本机实例）：无项目上下文时问「那价格多少，到店付多少」，
    // 模型会答成「您问的是胡子项目的价格吧？到店再付272元」，问「你们做这个多少钱，订金要付多少」
    // 甚至编出「订金28元、到店再付272元、总共300元」（真实订金 8 元）。四个项目价格互不相同，
    // 报错价是最容易引发客诉的事故（AI 回复可被客户截图取证），必须在进入检索前拦住。
    private static final Map<String, List<String>> LOCAL_ASPECT_KEYWORDS = Map.of(
            "价格", List.of("价格", "多少钱", "价钱", "收费", "费用", "活动价", "优惠", "订金", "定金", "尾款",
                    "到店付", "还要付", "补多少", "到店再付", "到店需要付", "付多少"),
            "疗程", List.of("几次", "多少次", "疗程", "周期", "多久见效", "做多久"),
            "效果", List.of("效果", "有效吗", "有用吗", "能淡", "能去掉", "会复发", "维持多久", "管多久", "保持多久", "撑多久"),
            "恢复", List.of("恢复", "上班", "红肿", "疼吗", "痛吗", "休息", "恢复期"),
            "安全", List.of("适合", "能做吗", "可以做吗", "禁忌", "风险", "副作用", "安全吗", "注意"),
            "流程", List.of("怎么做", "怎么弄", "流程", "步骤", "原理", "操作", "仪器", "设备", "打针", "手术", "开刀", "无创"));

    private static boolean mentionsProjectProperty(String text) {
        if (text == null || text.isBlank()) return false;
        String n = normalize(text);
        for (List<String> keywords : LOCAL_ASPECT_KEYWORDS.values()) {
            for (String kw : keywords) {
                if (n.contains(normalize(kw))) return true;
            }
        }
        return false;
    }

    /**
     * 维度判定优先级。Map.of 的迭代顺序不做保证，若直接遍历 entrySet，
     * 同时含多个维度词的句子（如“多少钱、几次能好”）返回值会在 JVM 之间漂移。
     * 这里显式定序，并把「价格」放在最前——报错价的代价远高于其它维度答偏。
     */
    private static final List<String> ASPECT_PRIORITY =
            List.of("价格", "恢复", "疗程", "效果", "安全", "流程");

    private static String inferAspect(String text) {
        if (text == null || text.isBlank()) return null;
        String n = normalize(text);
        for (String aspect : ASPECT_PRIORITY) {
            for (String kw : LOCAL_ASPECT_KEYWORDS.getOrDefault(aspect, List.of())) {
                if (n.contains(normalize(kw))) return aspect;
            }
        }
        return null;
    }

    private static String detectAspect(String text, Map<String, List<String>> aspects) {
        String value = normalize(text);
        for (Map.Entry<String, List<String>> entry : aspects.entrySet()) {
            if (entry.getValue().stream().map(MessageUnderstanding::normalize).anyMatch(value::contains)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(normalize(keyword))) return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text
                .replaceAll("[\\p{P}\\p{S}\\s]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String genericQuestion(ConfigService config) {
        String fallback = "您想了解哪个项目呢？可以说一下您最关心的一件事，比如价格、效果、流程或门店。";
        return config == null ? fallback : config.get("intent.genericQuestion", fallback);
    }

    private static String projectQuestion(String project, ConfigService config) {
        String fallback = "您想先了解{project}的哪方面呢？比如价格、效果、需要几次或注意事项。";
        String template = config == null ? fallback : config.get("intent.projectQuestionTemplate", fallback);
        return template.replace("{project}", project);
    }

    private static String aspectQuestion(String aspect, ConfigService config) {
        if (config != null) {
            String configured = AiConfigCatalog.stringMap(config, "intent.aspectQuestions").get(aspect);
            if (configured != null && !configured.isBlank()) return configured;
        }
        return switch (aspect) {
            case "价格" -> "您想问哪个项目的价格呢？告诉我项目名称，我再帮您查准确资料。";
            case "疗程" -> "您想问哪个项目需要做几次呢？告诉我项目名称，我再帮您查准确资料。";
            case "效果" -> "您想了解哪个项目的效果呢？告诉我项目名称，我再帮您查准确资料。";
            case "恢复" -> "您想问哪个项目的恢复情况呢？告诉我项目名称，我再帮您查准确资料。";
            case "安全" -> "您想问哪个项目是否适合做呢？告诉我项目名称，我再帮您查准确资料。";
            case "流程" -> "您想问哪个项目使用什么仪器、怎么操作呢？告诉我项目名称，我再帮您查准确资料。";
            default -> genericQuestion(config);
        };
    }

    /** 供首次项目介绍去重使用，别名配置修改后也立即生效。 */
    public static boolean mentionsProject(String text, String project, ConfigService config) {
        if (text == null || text.isBlank() || project == null || project.isBlank()) return false;
        return projectRules(config).stream()
                .filter(rule -> project.equals(rule.canonical()))
                .flatMap(rule -> rule.aliases().stream())
                .anyMatch(text::contains);
    }

    private static List<ProjectRule> projectRules(ConfigService config) {
        return projectRules(config, null);
    }

    private static List<ProjectRule> projectRules(ConfigService config,
                                                  Map<String, List<String>> catalogRules) {
        if (catalogRules != null && !catalogRules.isEmpty()) {
            return catalogRules.entrySet().stream()
                    .map(entry -> new ProjectRule(entry.getKey(), entry.getValue()))
                    .toList();
        }
        if (config == null) return PROJECTS;
        return AiConfigCatalog.stringListMap(config, "intent.projects").entrySet().stream()
                .map(entry -> new ProjectRule(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Map<String, List<String>> defaultAspects() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("价格", List.of("多少钱", "价格", "价钱", "收费", "费用", "活动价", "优惠",
                "订金", "定金", "尾款", "到店还要付", "还要付多少", "补多少钱"));
        map.put("疗程", List.of("几次", "多少次", "疗程", "周期", "多久见效", "做多久"));
        map.put("效果", List.of("效果", "有效吗", "有用吗", "能淡", "能去掉", "会复发", "维持多久"));
        map.put("恢复", List.of("恢复", "上班", "红肿", "疼吗", "痛吗", "休息几天", "恢复期"));
        map.put("安全", List.of("适合", "能做吗", "可以做吗", "禁忌", "风险", "副作用", "安全吗", "注意事项", "注意什么"));
        map.put("流程", List.of("怎么做", "怎么弄", "流程", "步骤", "原理", "操作", "仪器", "设备",
                "打针", "手术", "开刀", "无创"));
        map.put("案例", List.of("案例", "照片", "图片", "对比图", "效果图"));
        map.put("项目介绍", List.of("怎么样", "是什么", "介绍一下", "了解一下"));
        return map;
    }
}
