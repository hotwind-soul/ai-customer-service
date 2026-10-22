package com.example.aics.knowledge;

import java.util.List;
import java.util.regex.Pattern;

/** 识别不应进入客户向量检索的动态价格内容。 */
final class DynamicPriceGovernance {

    static final String STRATEGY = "dynamic-price-no-vector-v1";
    static final String REASON = "包含价格、订金或尾款等动态数据，请在项目中心维护并实时读取";

    private static final List<String> PRICE_TERMS = List.of(
            "价格", "多少钱", "价钱", "收费", "费用", "活动价", "优惠价", "原价",
            "订金", "定金", "尾款", "到店再付", "补款", "加价", "便宜", "优惠", "折扣", "块钱");
    private static final Pattern MONEY_AMOUNT = Pattern.compile(
            "(?<!\\d)\\d+(?:\\.\\d{1,2})?\\s*(?:元|块钱|块)(?!\\d)");

    private DynamicPriceGovernance() {}

    static Decision inspect(String content, String knowledgeType) {
        if (content == null || content.isBlank() || "offer".equals(knowledgeType)) {
            return Decision.allowed();
        }
        String question = faqQuestion(content);
        if (question != null && containsAny(question, PRICE_TERMS)) {
            return Decision.blocked();
        }
        if (MONEY_AMOUNT.matcher(content).find() && containsAny(content, PRICE_TERMS)) {
            return Decision.blocked();
        }
        return Decision.allowed();
    }

    private static String faqQuestion(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("问：")) return trimmed.substring(2).trim();
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    record Decision(boolean governed, String reason) {
        static Decision blocked() { return new Decision(true, REASON); }
        static Decision allowed() { return new Decision(false, null); }
    }
}
