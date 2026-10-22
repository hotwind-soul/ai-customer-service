package com.example.aics.chat;

import java.util.List;

/**
 * 动态业务事实的统一权威来源策略。
 *
 * <p>这些信息会随运营配置变化，不允许在结构化中心没有回答时退回到旧向量文档。
 * 策略独立于 ChatEngine 的 if 顺序，新增路由时仍有最后一道事实冲突保护。</p>
 */
public final class FactAuthorityPolicy {

    public enum Domain {
        PRICE,
        PROJECT_SCOPE,
        PROJECT_CATALOG,
        STORE,
        ACTIVITY_POLICY,
        BUSINESS_POLICY,
        GENERAL_KNOWLEDGE
    }

    public enum Source {
        PROJECT_CENTER,
        STORE_CENTER,
        BUSINESS_FACT_CENTER,
        KNOWLEDGE_BASE
    }

    public record Decision(Domain domain, Source source, boolean knowledgeFallbackAllowed,
                           String reason, String unavailableAnswer) {}

    private static final List<String> PROJECT_SCOPE_TERMS = List.of(
            "哪些部位", "什么部位", "几个部位", "多少个部位", "服务范围", "操作范围",
            "包含部位", "包括部位", "覆盖部位", "包含区域", "包括区域");
    private static final List<String> PROJECT_CATALOG_TERMS = List.of(
            "做什么项目", "什么项目可以做", "都做什么", "主要做什么",
            "有哪些项目", "有什么项目", "项目列表");
    private static final List<String> STORE_TERMS = List.of(
            "门店", "附近", "地址", "位置", "导航", "哪家店", "哪里有店", "店在哪", "几家店", "多少家店",
            "营业时间", "几点开门", "几点关门", "营业到几点");
    private static final List<String> ACTIVITY_TERMS = List.of(
            "活动结束", "活动到期", "什么时候结束", "活动还有", "还有名额", "剩余名额",
            "不预约", "直接到店", "接车", "接送", "报销车费");
    private static final List<String> BUSINESS_POLICY_TERMS = List.of(
            "退款", "退费", "退钱", "退订金", "退定金", "订金能退", "定金能退",
            "支付方式", "微信支付", "支付宝", "刷卡", "医保", "分期", "发票", "开票",
            "预约收费", "预约要钱", "自动扣款", "隐形消费", "额外收费", "面诊收费", "检测收费");
    /** 价格意图的文本兜底：NLU 漏识别 aspect 时仍拦住价格问题。
     *  刻意不含 订金/定金/收费/退款 等词——它们归 BUSINESS_POLICY_TERMS 管，避免路由冲突。 */
    private static final List<String> PRICE_TERMS = List.of(
            "价格", "多少钱", "价钱", "价位", "费用", "报价", "价目", "活动价", "优惠价", "到店付");

    public Decision decide(String message, MessageUnderstanding.Result understanding) {
        String text = message == null ? "" : message;
        String project = understanding == null ? null : understanding.project();
        String aspect = understanding == null ? null : understanding.aspect();

        if ("价格".equals(aspect) || containsAny(text, PRICE_TERMS)) {
            if (project != null && !project.isBlank()) {
                return protectedDecision(Domain.PRICE, Source.PROJECT_CENTER,
                        "价格、订金和尾款只允许从项目中心已发布报价读取",
                        "这个项目的最新价格正在核对中，我先不引用旧知识库价格。您可以回复“转人工”帮您确认。");
            }
            // 命中价格意图但 NLU 未识别出项目：同样禁止回退知识库（防止旧文档报价外泄）。
            return protectedDecision(Domain.PRICE, Source.PROJECT_CENTER,
                    "命中价格意图但未识别到具体项目，禁止引用知识库价格",
                    "为避免报错价格，请先告诉我想了解的项目名称（如抗衰、淡斑、眼袋、胡子），我再帮您查最新报价。您也可以回复“转人工”帮您确认。");
        }
        if (containsAny(text, PROJECT_SCOPE_TERMS)) {
            return protectedDecision(Domain.PROJECT_SCOPE, Source.PROJECT_CENTER,
                    "服务范围和包含部位只允许从项目中心读取",
                    "项目中心目前没有可用的服务范围信息，我不按旧资料推断。您可以回复“转人工”进一步确认。");
        }
        if (containsAny(text, PROJECT_CATALOG_TERMS)) {
            return protectedDecision(Domain.PROJECT_CATALOG, Source.PROJECT_CENTER,
                    "当前可用项目只允许从项目中心读取",
                    "当前可用项目正在更新中，我先不引用旧资料。您可以回复“转人工”确认。");
        }
        if (containsAny(text, STORE_TERMS)) {
            return protectedDecision(Domain.STORE, Source.STORE_CENTER,
                    "门店地址、支持项目和营业信息只允许从门店资料读取",
                    "门店资料目前没有找到可靠结果，我先不引用旧知识库地址。请告诉我城市和区县，或回复“转人工”。");
        }
        if (containsAny(text, ACTIVITY_TERMS)) {
            return protectedDecision(Domain.ACTIVITY_POLICY, Source.BUSINESS_FACT_CENTER,
                    "活动状态、名额和接送政策只允许从已发布业务事实读取",
                    "这项活动政策目前需要按最新情况确认，我先不引用旧知识库说法。您可以回复“转人工”。");
        }
        if (containsAny(text, BUSINESS_POLICY_TERMS)) {
            return protectedDecision(Domain.BUSINESS_POLICY, Source.BUSINESS_FACT_CENTER,
                    "退款、支付、开票和收费规则只允许从已发布业务事实读取",
                    "这项政策需要按当前已发布规则和实际订单确认，我先不引用旧知识库说法。您可以回复“转人工”。");
        }
        return new Decision(Domain.GENERAL_KNOWLEDGE, Source.KNOWLEDGE_BASE, true,
                "项目中心和业务工具无专属结构化答案，可进入知识检索", null);
    }

    private Decision protectedDecision(Domain domain, Source source, String reason, String unavailableAnswer) {
        return new Decision(domain, source, false, reason, unavailableAnswer);
    }

    private boolean containsAny(String text, List<String> terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }
}
