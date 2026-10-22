package com.example.aics.quality;

import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 不调用模型的确定性对话风险扫描器。分值仅表示规则扣分结果，不代表完整语义质量。 */
public final class QualityRuleEngine {

    public record Message(long id, String role, String content, String matchType) {
        public Message {
            role = role == null ? "" : role;
            content = content == null ? "" : content.trim();
            matchType = matchType == null ? "" : matchType;
        }
    }

    public record Issue(String code, String category, String dimension, int deduction,
                        String description, String evidence) {}

    /** 评分规则的可读说明，供前端“评分规则说明”面板展示，由 DEFAULTS 派生，保证与评分逻辑一致。 */
    public record RuleDescriptor(String code, String category, String dimension, int deduction,
                                 String trigger, String suggestion, String note) {}

    public record Score(int intent, int accuracy, int context, int safety,
                        int naturalness, int resolution, int handoff,
                        List<Issue> issues, List<String> suggestions) {
        public int total() {
            return intent + accuracy + context + safety + naturalness + resolution + handoff;
        }
    }

    /**
     * 单条规则的可覆盖配置，存于 system_config 的 quality.rules.json。
     * 任意字段为 null 都表示“沿用引擎默认”，因此默认路径（配置缺失/解析失败）== 今天行为，零回归风险。
     */
    public record RuleConfig(Boolean enabled, Map<String, Integer> deductions,
                             String suggestion, String pattern) {}

    /** 合并默认与配置后的“生效规则”，供管理端编辑与说明面板使用。 */
    public record RuleView(String code, String category, String dimension, int deduction,
                           String trigger, String suggestion, String note,
                           boolean enabled, String pattern, Map<String, Integer> allDeductions) {}

    /** 单条规则的默认定义（检测逻辑仍硬编码在 evaluate()；这里描述扣分/建议/说明）。 */
    private record RuleDef(String code, String category, String primaryDimension,
                           LinkedHashMap<String, Integer> deductions, String suggestion,
                           String description, String note, boolean supportsPattern) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, RuleConfig>> CONFIG_MAP =
            new TypeReference<>() {};

    private static final Pattern ABSOLUTE_PROMISE = Pattern.compile(
            "(立竿见影|百分之百|100[%％]|永不反弹|永久不反弹|绝对安全|零风险|完全不痛|保证有效|保证能|无条件退款)");
    private static final Pattern MEDICATION_ADVICE = Pattern.compile(
            "(自行|可以|直接|马上)?(停药|减药|换药|继续服药|继续用药|不用咨询医生)");
    private static final Pattern SAFE_MEDICATION_BOUNDARY = Pattern.compile(
            "(不要|不能|不可|不建议|禁止|请勿|不得)(?:为了[^。！？\\n]{0,16})?(?:自行)?"
                    + "(停药|减药|换药|继续服药|继续用药)"
                    + "(?:[、,，或和以及\\s]*(停药|减药|换药|继续服药|继续用药))*");
    private static final Pattern ABUSE = Pattern.compile("(傻逼|煞笔|沙比|妈的|操你妈|垃圾客服|废物|滚蛋)");
    private static final Pattern THREAT = Pattern.compile("(杀了你|弄死你|砍死你|打死你|堵你们门|人肉你|曝光你们|12315)");
    private static final Pattern HUMAN_REQUEST = Pattern.compile("(转人工|找人工|人工客服|真人客服|人工服务|找客服|投诉|退款|退钱)");
    private static final Pattern HANDOFF_CLAIM = Pattern.compile("(已为您|帮您|正在|马上).{0,8}(转接|接入).{0,6}(人工|客服)|进入.{0,4}(人工|客服).{0,4}(队列|排队)");
    private static final Pattern ASK_PROJECT = Pattern.compile("(想|要|需要)?(先)?了解(哪|哪个|什么)(个)?项目|哪个项目|项目名称");
    private static final Pattern PRICE_QUESTION = Pattern.compile("(多少钱|价格|价钱|收费|费用)");
    private static final Pattern PRICE_ANSWER = Pattern.compile("([0-9一二三四五六七八九十百千]+\\s*(元|块)|价格|费用|收费|暂时没有.*价格|没有找到.*价格|人工.*核实)");
    private static final Pattern SAFETY_RESPONSE = Pattern.compile("(理解您|很生气|不满意|停止辱骂|不要使用辱骂|威胁|具体问题|转人工|人工客服)");

    /** 全部规则的默认定义，顺序即展示顺序，也是评分逻辑的唯一事实来源。 */
    private static final LinkedHashMap<String, RuleDef> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("no_ai_reply", new RuleDef("no_ai_reply", "应答完整性", "accuracy",
                m("accuracy", 20), "确认消息是否正常落库，或检查会话是否在 AI 回复前转入人工队列。",
                "会话中没有可评分的 AI 回复", null, false));
        DEFAULTS.put("unresolved", new RuleDef("unresolved", "问题解决", "resolution",
                m("resolution", 10), "确认是消息漏落库还是提前转入人工队列。",
                "客户问题没有得到 AI 回复。", null, false));
        DEFAULTS.put("absolute_promise", new RuleDef("absolute_promise", "安全合规", "safety",
                m("safety", 12, "accuracy", 4),
                "删除绝对化承诺，改为说明效果因人而异，并以到店面诊评估为准。",
                "AI 使用了绝对化效果、安全或退款承诺（附近无否定语时命中）。", "另扣回答准确性 4 分。", true));
        DEFAULTS.put("medication_advice", new RuleDef("medication_advice", "安全合规", "safety",
                m("safety", 15),
                "涉及用药时只提示咨询原医生并由专业人员面诊评估，不给出停药、减药或继续用药指令。",
                "AI 可能指导客户自行停药 / 减药 / 换药 / 继续用药（已排除“不要自行停药”等合规边界提醒）。",
                null, false));
        DEFAULTS.put("wrong_store_hours", new RuleDef("wrong_store_hours", "事实准确", "accuracy",
                m("accuracy", 10), null,
                "回复同时出现“门店 / 营业”和客服在线时间，把客服时间当成门店营业时间。", null, false));
        DEFAULTS.put("wrong_agent_hours", new RuleDef("wrong_agent_hours", "事实准确", "accuracy",
                m("accuracy", 10), null,
                "回复同时出现“客服 / 坐席 / 人工”和门店营业时间，把门店时间当成客服时间。", null, false));
        DEFAULTS.put("repeat_project_question", new RuleDef("repeat_project_question", "意图识别", "intent",
                m("intent", 9, "context", 6, "resolution", 4),
                "识别到客户已报项目后，应直接详细介绍或回答具体问题，不再重复询问项目名称。",
                "客户已经说明项目，AI 又追问想了解哪个项目 / 哪个项目。", "另扣上下文连续性 6、问题解决 4。", false));
        DEFAULTS.put("price_not_answered", new RuleDef("price_not_answered", "问题解决", "resolution",
                m("resolution", 5, "accuracy", 3),
                "价格问题应优先给出已审核价格；资料不足时明确说明并提供人工核实，不要绕开问题。",
                "客户询问价格（多少钱 / 价格 / 收费 / 费用），AI 未回答、未说明资料边界、也未澄清（非 CLARIFY 类）。",
                "另扣回答准确性 3。", false));
        DEFAULTS.put("unsafe_emotion_response", new RuleDef("unsafe_emotion_response", "情绪安全", "safety",
                m("safety", 10, "naturalness", 3),
                "先识别情绪并降温，明确沟通边界，再引导客户说明具体问题；严重威胁按规则转人工。",
                "面对辱骂、威胁或强烈情绪时没有进行降温和边界回应（未含理解您 / 停止辱骂 / 转人工等，非 SAFETY 类）。",
                "另扣话术自然度 3。", false));
        DEFAULTS.put("missed_handoff", new RuleDef("missed_handoff", "转人工", "handoff",
                m("handoff", 5, "resolution", 3),
                "明确的转人工、投诉或退款请求应立即接入在线坐席或进入正常人工队列。",
                "客户明确要求人工 / 投诉 / 退款（转人工 / 找人工 / 人工客服 / 退款 / 退钱等），AI 未进入人工流程（非 AGENT 类）。",
                "另扣问题解决 3。", false));
        DEFAULTS.put("unnecessary_handoff", new RuleDef("unnecessary_handoff", "转人工", "handoff",
                m("handoff", 3),
                "仅在客户明确要求人工、投诉退款、严重威胁或系统确实无法安全处理时转人工。",
                "AI 声称已转接 / 接入人工，但上一条客户无明确人工诉求、且前 6 轮也无升级信号。", null, false));
        DEFAULTS.put("overlong_reply", new RuleDef("overlong_reply", "话术自然度", "naturalness",
                m("naturalness", 3),
                "把长回复拆成重点明确的短段落，一次只回答客户当前最关心的一件事。",
                "单条 AI 回复超过 500 字。", null, false));
        DEFAULTS.put("multiple_questions", new RuleDef("multiple_questions", "话术自然度", "naturalness",
                m("naturalness", 2),
                "每轮最多问客户一个问题，减少问卷式沟通。",
                "一条回复包含超过 1 个问号（? 或 ？）。", null, false));
        DEFAULTS.put("repeated_reply", new RuleDef("repeated_reply", "上下文连续", "context",
                m("context", 5, "naturalness", 2),
                "客户重复询问时先承认上一轮可能没讲清楚，再换一种更明确的说法回答。",
                "同一会话中 AI 重复发送高度相同的回复（去空白标点后重复，>=12 字）。", "另扣话术自然度 2。", false));
    }

    private static LinkedHashMap<String, Integer> m(Object... kv) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put((String) kv[i], (Integer) kv[i + 1]);
        return map;
    }

    private final ConfigService config;

    public QualityRuleEngine() { this(null); }
    public QualityRuleEngine(ConfigService config) { this.config = config; }

    /** 全部已知规则 code。 */
    public static Set<String> knownRuleCodes() { return DEFAULTS.keySet(); }

    public Score evaluate(List<Message> input) {
        List<Message> messages = input == null ? List.of() : input;
        Set<String> projects = projectAliases();
        String agentHours = configText("agent.workHours");
        String storeHours = configText("store.businessHours");
        Dims dims = new Dims();
        List<Issue> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        Map<String, RuleConfig> overrides = loadOverrides();

        List<Message> aiMessages = messages.stream().filter(m -> "assistant".equals(m.role())).toList();
        if (aiMessages.isEmpty()) {
            dims.intent = 0; dims.accuracy = 0; dims.context = 0; dims.naturalness = 0; dims.resolution = 0;
            applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("no_ai_reply"),
                    overrides.get("no_ai_reply"), "未发现 role=assistant 的消息", null);
            applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("unresolved"),
                    overrides.get("unresolved"), "会话缺少 AI 应答", null);
            suggestions.add("确认消息是否正常落库，或检查会话是否在 AI 回复前转入人工队列。");
        }

        for (int i = 0; i < messages.size(); i++) {
            Message current = messages.get(i);
            if (!"assistant".equals(current.role())) continue; // 人工坐席消息绝不作为 AI 评分对象
            String answer = current.content();
            String previousUser = previousUser(messages, i);

            RuleConfig cfg;
            Pattern ap;
            cfg = overrides.get("absolute_promise");
            ap = patternOf(cfg, ABSOLUTE_PROMISE);
            if (isEnabled(cfg) && containsUnsafeAbsolutePromise(answer, ap)) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("absolute_promise"),
                        cfg, excerpt(answer), null);
            }
            cfg = overrides.get("medication_advice");
            if (isEnabled(cfg) && containsUnsafeMedicationAdvice(answer)) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("medication_advice"),
                        cfg, excerpt(answer), null);
            }
            cfg = overrides.get("wrong_store_hours");
            if (isEnabled(cfg) && (answer.contains("门店") || answer.contains("营业")) && answer.contains(agentHours)) {
                String sugg = "门店营业时间固定使用 " + storeHours + "；客服在线时间才是 " + agentHours + "。";
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("wrong_store_hours"),
                        cfg, excerpt(answer), sugg);
            }
            cfg = overrides.get("wrong_agent_hours");
            if (isEnabled(cfg) && (answer.contains("客服") || answer.contains("坐席") || answer.contains("人工")) && answer.contains(storeHours)) {
                String sugg = "客服在线时间固定使用 " + agentHours + "；门店营业时间才是 " + storeHours + "。";
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("wrong_agent_hours"),
                        cfg, excerpt(answer), sugg);
            }
            cfg = overrides.get("repeat_project_question");
            if (isEnabled(cfg) && containsProject(projects, previousUser) && ASK_PROJECT.matcher(answer).find()) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("repeat_project_question"),
                        cfg, "客户：“" + excerpt(previousUser) + "” / AI：“" + excerpt(answer) + "”", null);
            }
            cfg = overrides.get("price_not_answered");
            if (isEnabled(cfg) && PRICE_QUESTION.matcher(previousUser).find() && !PRICE_ANSWER.matcher(answer).find()
                    && !"CLARIFY".equalsIgnoreCase(current.matchType())) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("price_not_answered"),
                        cfg, "客户：“" + excerpt(previousUser) + "” / AI：“" + excerpt(answer) + "”", null);
            }
            cfg = overrides.get("unsafe_emotion_response");
            if (isEnabled(cfg) && (ABUSE.matcher(previousUser).find() || THREAT.matcher(previousUser).find())
                    && !SAFETY_RESPONSE.matcher(answer).find() && !"SAFETY".equalsIgnoreCase(current.matchType())) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("unsafe_emotion_response"),
                        cfg, "客户：“" + excerpt(previousUser) + "” / AI：“" + excerpt(answer) + "”", null);
            }
            cfg = overrides.get("missed_handoff");
            if (isEnabled(cfg) && HUMAN_REQUEST.matcher(previousUser).find() && !HANDOFF_CLAIM.matcher(answer).find()
                    && !"AGENT".equalsIgnoreCase(current.matchType())) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("missed_handoff"),
                        cfg, "客户：“" + excerpt(previousUser) + "” / AI：“" + excerpt(answer) + "”", null);
            }
            cfg = overrides.get("unnecessary_handoff");
            if (isEnabled(cfg) && HANDOFF_CLAIM.matcher(answer).find() && !HUMAN_REQUEST.matcher(previousUser).find()
                    && !hasEarlierEscalationSignal(messages, i)) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("unnecessary_handoff"),
                        cfg, "AI：“" + excerpt(answer) + "”", null);
            }
            cfg = overrides.get("overlong_reply");
            if (isEnabled(cfg) && answer.length() > 500) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("overlong_reply"),
                        cfg, answer.length() + " 个字符", null);
            }
            cfg = overrides.get("multiple_questions");
            if (isEnabled(cfg) && countQuestionMarks(answer) > 1) {
                applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("multiple_questions"),
                        cfg, excerpt(answer), null);
            }
        }

        RuleConfig rr = overrides.get("repeated_reply");
        if (isEnabled(rr) && hasRepeatedAiReply(aiMessages)) {
            applyRule(seenCodes, issues, suggestions, dims, DEFAULTS.get("repeated_reply"),
                    rr, "发现重复 AI 文本", null);
        }

        return new Score(dims.intent, dims.accuracy, dims.context, dims.safety, dims.naturalness,
                dims.resolution, dims.handoff, List.copyOf(issues), List.copyOf(suggestions));
    }

    /** 七个维度分数，带 0 下限钳制。 */
    private static final class Dims {
        int intent = 20, accuracy = 20, context = 15, safety = 20, naturalness = 10, resolution = 10, handoff = 5;
        void deduct(String dim, int amount) {
            if (amount <= 0) return;
            switch (dim) {
                case "intent" -> intent = Math.max(0, intent - amount);
                case "accuracy" -> accuracy = Math.max(0, accuracy - amount);
                case "context" -> context = Math.max(0, context - amount);
                case "safety" -> safety = Math.max(0, safety - amount);
                case "naturalness" -> naturalness = Math.max(0, naturalness - amount);
                case "resolution" -> resolution = Math.max(0, resolution - amount);
                case "handoff" -> handoff = Math.max(0, handoff - amount);
            }
        }
    }

    private static boolean isEnabled(RuleConfig cfg) {
        return cfg == null || cfg.enabled() == null || cfg.enabled();
    }

    private static Pattern patternOf(RuleConfig cfg, Pattern fallback) {
        if (cfg == null || cfg.pattern() == null || cfg.pattern().isBlank()) return fallback;
        return compileSafe(cfg.pattern(), fallback);
    }

    private static Pattern compileSafe(String regex, Pattern fallback) {
        try { return Pattern.compile(regex); } catch (Exception e) { return fallback; }
    }

    /**
     * 命中规则后：按配置（覆盖默认）记录 Issue、对多维度扣分、添加建议。
     * 配置缺失/解析失败时 cfg 相关字段为 null → 行为与今天完全一致。
     */
    private void applyRule(Set<String> seen, List<Issue> issues, List<String> suggestions, Dims dims,
                           RuleDef def, RuleConfig cfg, String evidence, String runtimeSuggestion) {
        if (!isEnabled(cfg)) return;
        if (!seen.add(def.code)) return;
        Map<String, Integer> deds = (cfg != null && cfg.deductions() != null && !cfg.deductions().isEmpty())
                ? cfg.deductions() : def.deductions;
        String sugg = (cfg != null && cfg.suggestion() != null && !cfg.suggestion().isBlank())
                ? cfg.suggestion()
                : (runtimeSuggestion != null ? runtimeSuggestion : def.suggestion);
        int primary = deds.getOrDefault(def.primaryDimension, 0);
        issues.add(new Issue(def.code, def.category, def.primaryDimension, primary, def.description, evidence));
        deds.forEach(dims::deduct);
        if (sugg != null && !sugg.isBlank()) addSuggestion(suggestions, sugg);
    }

    /** 读取 system_config.quality.rules.json；任何异常都回退为空（全部用默认），保证零回归。 */
    private Map<String, RuleConfig> loadOverrides() {
        if (config == null) return Map.of();
        String raw = config.get("quality.rules.json", "");
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, RuleConfig> parsed = JSON.readValue(raw, CONFIG_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 合并默认与配置，返回当前生效规则（含 enabled/pattern/allDeductions），供管理端编辑与说明面板。 */
    public List<RuleView> effectiveRules() {
        String agentHours = configText("agent.workHours");
        String storeHours = configText("store.businessHours");
        Map<String, RuleConfig> overrides = loadOverrides();
        List<RuleView> views = new ArrayList<>();
        for (RuleDef def : DEFAULTS.values()) {
            RuleConfig cfg = overrides.get(def.code);
            boolean enabled = isEnabled(cfg);
            Map<String, Integer> deds = (cfg != null && cfg.deductions() != null && !cfg.deductions().isEmpty())
                    ? cfg.deductions() : def.deductions;
            String sugg = (cfg != null && cfg.suggestion() != null && !cfg.suggestion().isBlank())
                    ? cfg.suggestion()
                    : (def.suggestion == null
                        ? ("wrong_store_hours".equals(def.code) || "wrong_agent_hours".equals(def.code)
                            ? "门店营业时间固定使用 " + storeHours + "；客服在线时间才是 " + agentHours + "。"
                            : null)
                        : def.suggestion);
            String pattern = (def.supportsPattern && cfg != null && cfg.pattern() != null && !cfg.pattern().isBlank())
                    ? cfg.pattern() : (def.supportsPattern ? ABSOLUTE_PROMISE.pattern() : null);
            int primary = deds.getOrDefault(def.primaryDimension, 0);
            views.add(new RuleView(def.code, def.category, def.primaryDimension, primary,
                    def.description, sugg, def.note, enabled, pattern, Map.copyOf(deds)));
        }
        return views;
    }

    /**
     * 计算与默认的差异（delta），仅保留确实偏离默认的配置，写入 quality.rules.json。
     * 这样未改动项始终沿用代码默认，后续引擎升级默认会自动生效。返回 null 表示该规则无差异。
     */
    public static RuleConfig computeOverride(String code, RuleConfig incoming) {
        RuleDef def = DEFAULTS.get(code);
        if (def == null || incoming == null) return null;
        Boolean enabled = (incoming.enabled() != null && !incoming.enabled()) ? Boolean.FALSE : null;
        Map<String, Integer> deductions = null;
        if (incoming.deductions() != null && !incoming.deductions().equals(def.deductions)) {
            deductions = new LinkedHashMap<>(incoming.deductions());
        }
        String suggestion = (incoming.suggestion() != null && !incoming.suggestion().isBlank()
                && !incoming.suggestion().equals(def.suggestion)) ? incoming.suggestion() : null;
        String pattern = null;
        if (def.supportsPattern && incoming.pattern() != null && !incoming.pattern().isBlank()
                && !incoming.pattern().equals(ABSOLUTE_PROMISE.pattern())) {
            pattern = incoming.pattern();
        }
        if (enabled == null && deductions == null && suggestion == null && pattern == null) return null;
        return new RuleConfig(enabled, deductions, suggestion, pattern);
    }

    private static void addSuggestion(List<String> suggestions, String suggestion) {
        if (!suggestions.contains(suggestion)) suggestions.add(suggestion);
    }

    private static String previousUser(List<Message> messages, int before) {
        for (int i = before - 1; i >= 0; i--) if ("user".equals(messages.get(i).role())) return messages.get(i).content();
        return "";
    }

    private static boolean containsProject(Set<String> projects, String text) {
        String value = text == null ? "" : text;
        return projects.stream().anyMatch(value::contains);
    }

    /** 从配置读取项目标准名与别名（intent.projects），作为“已说明项目”判定集合；配置异常时回落目录默认。 */
    private Set<String> projectAliases() {
        Map<String, List<String>> map = AiConfigCatalog.stringListMap(config, "intent.projects");
        Set<String> all = new HashSet<>();
        map.forEach((key, aliases) -> { all.add(key); if (aliases != null) all.addAll(aliases); });
        return all;
    }

    private String configText(String key) {
        String fallback = AiConfigCatalog.defaultValue(key);
        return config == null ? fallback : config.get(key, fallback);
    }

    private static boolean hasEarlierEscalationSignal(List<Message> messages, int before) {
        int start = Math.max(0, before - 6);
        for (int i = start; i < before; i++) {
            if ("user".equals(messages.get(i).role())
                    && (HUMAN_REQUEST.matcher(messages.get(i).content()).find()
                    || THREAT.matcher(messages.get(i).content()).find())) return true;
        }
        return false;
    }

    private static int countQuestionMarks(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '?' || text.charAt(i) == '？') count++;
        return count;
    }

    private static boolean hasRepeatedAiReply(List<Message> messages) {
        Set<String> seen = new HashSet<>();
        for (Message message : messages) {
            String normalized = message.content().toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？!?、；;：:]", "");
            if (normalized.length() >= 12 && !seen.add(normalized)) return true;
        }
        return false;
    }

    /** 只检查承诺词附近的否定语，避免“不能保证效果，但绝对安全”被整句错误放过。 */
    private static boolean containsUnsafeAbsolutePromise(String answer, Pattern absolutePromise) {
        var matcher = absolutePromise.matcher(answer);
        while (matcher.find()) {
            int from = Math.max(0, matcher.start() - 8);
            String local = answer.substring(from, matcher.end());
            if (!local.matches(".*(不能|无法|不可以|不可|没有|不存在|并非|不是|不代表|不应|不会|禁止).{0,5}" +
                    Pattern.quote(matcher.group()) + "$")) return true;
        }
        return false;
    }

    /**
     * 移除“不要自行停药/不要为了项目自行停药、减药或继续用药”这类明确安全边界后，
     * 再检测剩余文本，避免把合规提醒本身误报成用药指导。
     */
    private static boolean containsUnsafeMedicationAdvice(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String withoutSafeBoundaries = SAFE_MEDICATION_BOUNDARY.matcher(answer).replaceAll("");
        return MEDICATION_ADVICE.matcher(withoutSafeBoundaries).find();
    }

    private static String excerpt(String value) {
        if (value == null) return "";
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 120 ? singleLine : singleLine.substring(0, 120) + "…";
    }

    /** 返回全部评分规则的静态说明（派生自 DEFAULTS），作为前端“评分规则说明”面板的单一数据源。 */
    public static List<RuleDescriptor> rulesReference() {
        List<RuleDescriptor> list = new ArrayList<>();
        for (RuleDef def : DEFAULTS.values()) {
            int primary = def.deductions.getOrDefault(def.primaryDimension, 0);
            list.add(new RuleDescriptor(def.code, def.category, def.primaryDimension, primary,
                    def.description, def.suggestion, def.note));
        }
        return list;
    }
}
