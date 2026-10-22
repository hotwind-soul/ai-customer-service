package com.example.aics.chat;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Harness 的确定性回归断言。
 *
 * <p>回答要点和禁止内容均使用相同的小型语法：分号或换行分隔检查项，
 * {@code |} 分隔同一检查项内的可选表达。例如 {@code 298元；8元|订金八元}。
 * 断言只做规范化后的文本匹配，不调用裁判模型，因此结果稳定且没有额外模型成本。</p>
 */
public final class HarnessAssertions {
    public static final String VERSION = "harness-v2";
    private static final Pattern NUMBER = Pattern.compile(
            "[+\\-−]?(?:(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?|\\.[0-9]+)(?:\\s*%)?");

    private HarnessAssertions() {
    }

    public record Spec(
            String expectedReplyType,
            String expectedAnswerPoints,
            String forbiddenAnswerPoints,
            String expectedAuthorityDomain,
            String expectedAuthoritySource,
            Boolean expectedModelInvoked
    ) {
        public boolean requiresAnswer() {
            return !blank(expectedAnswerPoints) || !blank(forbiddenAnswerPoints)
                    || Boolean.TRUE.equals(expectedModelInvoked);
        }

        public boolean empty() {
            return blank(expectedReplyType)
                    && blank(expectedAnswerPoints)
                    && blank(forbiddenAnswerPoints)
                    && blank(expectedAuthorityDomain)
                    && blank(expectedAuthoritySource)
                    && expectedModelInvoked == null;
        }
    }

    public record Actual(
            String replyType,
            String answer,
            String authorityDomain,
            String authoritySource,
            boolean modelInvoked
    ) {
    }

    public record Check(String key, String label, boolean passed, String expected, String actual) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("label", label);
            result.put("passed", passed);
            result.put("expected", expected == null ? "" : expected);
            result.put("actual", actual == null ? "" : actual);
            return result;
        }
    }

    public record Result(boolean passed, List<Check> checks, List<String> failures) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", passed);
            result.put("checks", checks.stream().map(Check::toMap).toList());
            result.put("failures", failures);
            return result;
        }
    }

    public static Result evaluate(Spec spec, Actual actual) {
        return evaluate(spec, actual, null);
    }

    public static Result evaluate(Spec spec, Actual actual, String executionError) {
        if (!blank(executionError)) return failedExecution(executionError);
        if (spec == null || spec.empty()) return new Result(true, List.of(), List.of());
        Actual value = actual == null ? new Actual("", "", "", "", false) : actual;
        if (spec.requiresAnswer() && blank(value.answer())) {
            return failedExecution("未取得有效回答，无法检查答案断言");
        }
        if (!validGroups(spec.expectedAnswerPoints()) || !validGroups(spec.forbiddenAnswerPoints())) {
            return failedExecution("答案断言包含空检查项，请填写有效内容");
        }
        List<Check> checks = new ArrayList<>();

        if (!blank(spec.expectedReplyType())) {
            add(checks, "replyType", "回答路由",
                    normalize(spec.expectedReplyType()).equals(normalize(value.replyType())),
                    spec.expectedReplyType(), value.replyType());
        }
        if (!blank(spec.expectedAnswerPoints())) {
            List<String> missing = unmatchedRequiredGroups(value.answer(), spec.expectedAnswerPoints());
            add(checks, "answerPoints", "回答要点", missing.isEmpty(), spec.expectedAnswerPoints(),
                    missing.isEmpty() ? "全部命中" : "缺少：" + String.join("；", missing));
        }
        if (!blank(spec.forbiddenAnswerPoints())) {
            List<String> present = matchedGroups(value.answer(), spec.forbiddenAnswerPoints());
            add(checks, "forbiddenAnswerPoints", "禁止内容", present.isEmpty(), spec.forbiddenAnswerPoints(),
                    present.isEmpty() ? "未命中" : "出现：" + String.join("；", present));
        }
        if (!blank(spec.expectedAuthorityDomain())) {
            add(checks, "authorityDomain", "权威领域",
                    matchesValue(value.authorityDomain(), spec.expectedAuthorityDomain()),
                    spec.expectedAuthorityDomain(), value.authorityDomain());
        }
        if (!blank(spec.expectedAuthoritySource())) {
            add(checks, "authoritySource", "权威来源",
                    matchesValue(value.authoritySource(), spec.expectedAuthoritySource()),
                    spec.expectedAuthoritySource(), value.authoritySource());
        }
        if (spec.expectedModelInvoked() != null) {
            add(checks, "modelInvoked", "模型调用", spec.expectedModelInvoked() == value.modelInvoked(),
                    spec.expectedModelInvoked() ? "应调用" : "不应调用", value.modelInvoked() ? "已调用" : "未调用");
        }

        List<String> failures = checks.stream().filter(check -> !check.passed())
                .map(check -> check.label() + "：" + check.actual()).toList();
        return new Result(failures.isEmpty(), List.copyOf(checks), failures);
    }

    private static Result failedExecution(String reason) {
        return new Result(false, List.of(new Check("execution", "执行状态", false, "执行成功", reason)),
                List.of("执行状态：" + reason));
    }

    private static boolean validGroups(String spec) {
        if (blank(spec)) return true;
        List<String> groups = groups(spec);
        return !groups.isEmpty() && groups.stream().allMatch(group -> alternatives(group).stream()
                .map(HarnessAssertions::normalize).anyMatch(term -> !term.isBlank()));
    }

    private static boolean matchesValue(String actual, String expectedSpec) {
        String normalizedActual = normalize(actual);
        return alternatives(expectedSpec).stream().map(HarnessAssertions::normalize)
                .anyMatch(expected -> !expected.isBlank() && normalizedActual.equals(expected));
    }

    private static List<String> unmatchedRequiredGroups(String answer, String spec) {
        String normalizedAnswer = normalize(answer);
        List<String> missing = new ArrayList<>();
        for (String group : groups(spec)) {
            boolean matched = alternatives(group).stream().map(HarnessAssertions::normalize)
                    .anyMatch(term -> containsTerm(normalizedAnswer, term));
            if (!matched) missing.add(group);
        }
        return missing;
    }

    private static List<String> matchedGroups(String answer, String spec) {
        String normalizedAnswer = normalize(answer);
        List<String> matched = new ArrayList<>();
        for (String group : groups(spec)) {
            boolean present = alternatives(group).stream().map(HarnessAssertions::normalize)
                    .anyMatch(term -> containsTerm(normalizedAnswer, term));
            if (present) matched.add(group);
        }
        return matched;
    }

    private static List<String> groups(String spec) {
        if (blank(spec)) return List.of();
        return java.util.Arrays.stream(spec.split("[;；\\r\\n]+"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static List<String> alternatives(String group) {
        if (blank(group)) return List.of();
        return java.util.Arrays.stream(group.split("\\|"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        // 先把数值编码为完整 token，再清理普通文本的标点，防止小数、符号、百分号丢失。
        StringBuilder result = new StringBuilder();
        Matcher numbers = NUMBER.matcher(normalized);
        int end = 0;
        while (numbers.find()) {
            result.append(normalizeText(normalized.substring(end, numbers.start())));
            String raw = numbers.group().replace(",", "").replace('−', '-')
                    .replaceAll("\\s+", "");
            boolean percentage = raw.endsWith("%");
            String number = percentage ? raw.substring(0, raw.length() - 1) : raw;
            result.append('\uE000').append(new BigDecimal(number).stripTrailingZeros().toPlainString())
                    .append(percentage ? "%" : "").append('\uE001');
            end = numbers.end();
        }
        return result.append(normalizeText(normalized.substring(end))).toString();
    }

    private static String normalizeText(String value) {
        return value.replaceAll("[\\p{P}\\p{S}\\s\\uE000\\uE001]+", "");
    }

    /** 数值已编码为完整 token，包含匹配也不会把“8元”命中到“298元”中。 */
    private static boolean containsTerm(String answer, String term) {
        return term != null && !term.isBlank() && answer.contains(term);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void add(List<Check> checks, String key, String label, boolean passed,
                            String expected, String actual) {
        checks.add(new Check(key, label, passed, expected, actual));
    }
}
