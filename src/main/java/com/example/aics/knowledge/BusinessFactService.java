package com.example.aics.knowledge;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.chat.MessageUnderstanding;
import com.example.aics.config.ConfigService;
import com.example.aics.config.ScopedDataAccess;
import com.example.aics.project.ProjectCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 价格、营业时间、门店数量、活动状态等时效性事实的唯一读取入口。
 * 未发布或过期的事实不会对客户输出，避免向量文档里的旧活动话术抢答。
 */
@Service
public class BusinessFactService {

    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final ProjectCatalogService projectCatalog;

    public BusinessFactService(JdbcTemplate jdbc, ConfigService config) {
        this(jdbc, config, null);
    }

    @Autowired
    public BusinessFactService(JdbcTemplate jdbc, ConfigService config,
                               ProjectCatalogService projectCatalog) {
        this.jdbc = jdbc;
        this.config = config;
        this.projectCatalog = projectCatalog;
    }

    public record StructuredAnswer(String answer, String factKey) {}

    /** 同一项目首次咨询只使用项目中心已发布话术；历史价格表只允许生成中性数字兜底。 */
    public StructuredAnswer initialProjectPitch(String projectKey) {
        ProjectCatalogService.Offer offer = projectCatalog == null
                ? null : projectCatalog.publishedOffer(projectKey);
        if (offer != null && offer.saleCopy() != null && !offer.saleCopy().isBlank()) {
            return new StructuredAnswer(offer.saleCopy().trim(), "project-offer#" + projectKey + ".sale_copy");
        }
        String archivedPrice = priceFromArchivedKnowledge(projectKey);
        if (archivedPrice == null) return null;
        return new StructuredAnswer(archivedPrice
                + "具体服务范围、适用性和支持门店分别以项目中心、专业评估和门店资料为准。",
                "price-archive#" + projectKey);
    }

    public StructuredAnswer tryAnswer(String message, MessageUnderstanding.Result understanding) {
        String text = message == null ? "" : message;

        if (containsAny(text, "做什么项目", "什么项目可以做", "都做什么", "主要做什么",
                "有哪些项目", "有什么项目", "项目列表")) {
            List<String> projects = projectCatalog == null ? List.of() : projectCatalog.publishedNames();
            String value = projects.isEmpty()
                    ? "当前可咨询项目正在更新中，您可以回复“转人工”进一步确认。"
                    : "当前可以咨询" + String.join("、", projects)
                    + "。子项目和具体方案会在选择主项目后进一步展示。";
            return new StructuredAnswer(value, "project.currentCatalog");
        }

        if (containsAny(text, "会不会动钱", "会动钱吗", "会不会扣钱", "会扣钱吗", "自动扣款",
                "预约要钱", "预约收费", "预约要付钱", "预约要交钱", "预约要付款")) {
            return new StructuredAnswer(
                    "只是登记预约不会自动扣款，也不会因为您发来称呼和手机号就扣钱。" +
                            "具体项目如果需要支付订金或其他费用，会先向您说明金额和规则，由您确认后再主动支付。",
                    "policy.bookingPayment");
        }

        if (asksAboutMaleEligibility(text)) {
            return new StructuredAnswer(
                    "男士也可以咨询抗衰和淡斑，并不是只限女性。是否适合、选择哪种方案，要结合个人皮肤和身体情况面诊评估。",
                    "eligibility.gender");
        }

        if (containsAny(text, "隐形消费", "额外收费", "乱收费")) {
            return new StructuredAnswer(
                    "到店操作前请先确认项目、活动价格和完整收费明细；我不能承诺绝对不存在额外费用，也不能承诺订金无条件可退。退款请按已发布规则和实际订单状态核实。",
                    "policy.chargeDisclosure");
        }

        if (containsAny(text, "面诊收费", "检测收费", "面诊免费", "检测免费")) {
            return new StructuredAnswer(
                    "检测或面诊是否收费，需要以当前已发布政策和具体门店确认为准；到店前可以先让人工客服帮您核实。",
                    "policy.consultationFee");
        }

        if (containsAny(text, "不预约", "直接到店")
                && containsAny(text, "活动价", "优惠", "活动")) {
            String value = publishedText("activity.bookingPolicy");
            return new StructuredAnswer(value != null ? value
                    : "是否需要预约、直接到店能否享受活动价，要以当前已发布的活动条件和具体门店确认为准；我不能承诺一定可以或制造恢复原价的紧迫性。",
                    "activity.bookingPolicy");
        }

        if (asksProjectScope(text)) {
            String projectKey = resolveProjectKey(text, understanding);
            if (projectKey != null) {
                String scope = projectCatalog == null ? null : projectCatalog.publishedServiceScope(projectKey);
                String value = scope == null || scope.isBlank()
                        ? "项目中心目前没有发布“" + projectKey + "”的具体服务范围或包含部位，"
                        + "因此不能按旧资料推断为固定数量。您可以回复“转人工”进一步确认。"
                        : projectKey + "当前已发布的服务范围是：" + scope.trim();
                return new StructuredAnswer(value, "project-scope#" + projectKey);
            }
        }

        if (understanding != null && understanding.complete()
                && "价格".equals(understanding.aspect()) && understanding.project() != null) {
            String projectKey = resolveProjectKey(text, understanding);
            ProjectCatalogService.Offer offer = projectCatalog == null
                    ? null : projectCatalog.publishedOffer(projectKey);
            String value = offer != null && offer.activityPrice() != null
                    ? priceFromOffer(projectKey, offer) : priceFromArchivedKnowledge(projectKey);
            String factKey = (offer == null ? "price-archive#" : "project-offer#") + projectKey;
            if (value != null && asksAboutBeardSurcharge(text)) {
                String surcharge = publishedText("price.hair.beardSurcharge");
                if (surcharge != null) {
                    value = appendSentence(value, surcharge);
                    factKey += "+price.hair.beardSurcharge";
                }
            }
            return new StructuredAnswer(value != null ? value
                    : "这个项目的最新价格正在核对中，我先不引用旧活动价。您可以回复“转人工”帮您确认。",
                    factKey);
        }

        boolean asksAgentHours = asksAgentHours(text);
        boolean asksStoreHours = asksStoreHours(text);
        if (asksAgentHours && asksStoreHours) {
            String agentHours = config.get("agent.workHours", "09:00-22:00");
            String storeHours = config.get("store.businessHours", "10:00-18:00");
            return new StructuredAnswer(
                    "门店营业时间是每天" + storeHours + "，节假日如有调整，以具体门店通知为准。"
                            + "人工客服在线时间是每天" + agentHours + "，在线时会优先接入，忙碌时按顺序排队。",
                    "hours.storeAndAgent");
        }

        // 客服在线时间优先于门店营业时间，避免“客服几点上班”被旧的“上班时间”规则答成门店时间。
        if (asksAgentHours) {
            String hours = config.get("agent.workHours", "09:00-22:00");
            return new StructuredAnswer("人工客服在线时间是每天" + hours + "。在线时会优先接入，忙碌时按顺序排队。",
                    "hours.agent.workHours");
        }

        if (asksStoreHours) {
            String hours = config.get("store.businessHours", "10:00-18:00");
            return new StructuredAnswer("门店营业时间是每天" + hours + "。节假日如有调整，以具体门店通知为准。",
                    "hours.store.businessHours");
        }

        if (containsAny(text, "停车", "停车位", "车位")) {
            return new StructuredAnswer(
                    "停车条件要看具体门店和当天现场情况，不能统一承诺一定有车位。您告诉我所在城市和区域，我先帮您查门店，再由门店确认停车指引。",
                    "store.parkingPolicy");
        }

        if (containsAny(text, "到店要排队", "到店排队", "不用排队", "免排队")) {
            return new StructuredAnswer(
                    "提前预约通常有助于门店安排接待，但不能保证到店完全不用等待，具体以门店当天排班和客流为准。",
                    "store.queuePolicy");
        }

        if (asksDepositRefund(text)) {
            return new StructuredAnswer(
                    "订金是否可退、怎么退，需要按当前已发布的退款规则和实际订单状态核实。我不能承诺无条件退款；您可以回复“转人工”由客服帮您确认。",
                    "policy.depositRefund");
        }

        if (containsAny(text, "退款", "退费", "退钱")) {
            return new StructuredAnswer(
                    "是否可以退款、退款金额和处理方式，需要按当前已发布规则及实际订单状态核实；我不能在线承诺无条件退款，人工客服会结合订单为您处理。",
                    "policy.refund");
        }

        if (containsAny(text, "支付方式", "可以刷卡", "能刷卡", "刷卡吗", "微信支付", "支付宝", "付现金", "能分期", "能刷医保")) {
            return new StructuredAnswer(
                    "不同门店支持的支付方式可能不同，刷卡、微信、支付宝、医保或分期请以具体门店当期实际支持情况为准。您告诉我门店或所在城市，我可以帮您进一步核实。",
                    "policy.paymentMethods");
        }

        if (containsAny(text, "开发票", "开发票吗", "能开票", "可以开票", "发票")) {
            return new StructuredAnswer(
                    "是否可以开具发票、开具什么类型，需要按具体门店和实际消费情况确认；到店前可以先让人工客服帮您核实。",
                    "policy.invoice");
        }

        if (containsAny(text, "多少家店", "多少门店", "几家店", "几家门店")) {
            ScopedDataAccess.Condition scope = ScopedDataAccess.condition("s");
            Integer count = scope.active()
                    ? jdbc.queryForObject("SELECT count(*) FROM store s WHERE " + scope.sql(),
                    Integer.class, scope.args().toArray())
                    : jdbc.queryForObject("SELECT count(*) FROM store", Integer.class);
            int n = count == null ? 0 : count;
            String answer = n > 0
                    ? "系统当前可以查询到 " + n + " 家门店，门店信息会持续更新。您告诉我所在城市，我可以帮您查附近门店。"
                    : "门店数量正在更新中。您告诉我所在城市，我可以先帮您查询附近门店。";
            return new StructuredAnswer(answer, "store.currentCount");
        }

        if (asksActivityAvailability(text)) {
            String value = publishedText("activity.availability");
            return new StructuredAnswer(value != null ? value
                    : "活动名额会实时变化，目前需要由客服按最新情况确认。您可以回复“转人工”帮您核实。",
                    "activity.availability");
        }

        if (containsAny(text, "免费接车", "接车条件", "接送条件", "报销车费", "路费报销",
                "有接送", "接送吗", "接车吗", "到店接送", "送回", "送回来", "往返接送",
                "来回接送", "接回去", "包接送")) {
            String value = publishedText("service.pickupPolicy");
            boolean asksReturnRide = containsAny(text, "送回", "送回来", "往返接送", "来回接送", "接回去");
            if (asksReturnRide && (value == null || !containsAny(value,
                    "送回", "返程", "往返", "来回", "只接不送", "不送回"))) {
                String knownPolicy = value == null ? "" : value + "\n";
                return new StructuredAnswer(knownPolicy
                        + "当前已发布资料未明确接车服务是否包含返程送回，因此不能承诺往返接送。返程安排请按具体门店确认。",
                        "service.pickupPolicy");
            }
            return new StructuredAnswer(value != null ? value
                    : "接车范围和车费标准需要按具体门店确认。您告诉我所在城市和区域，我帮您查询。",
                    "service.pickupPolicy");
        }
        return null;
    }

    private boolean asksAgentHours(String text) {
        return containsAny(text, "客服", "人工", "坐席", "真人")
                && containsAny(text, "几点上班", "几点下班", "上班时间", "在线时间", "工作时间", "服务时间", "几点在线",
                "营业时间", "几点营业", "营业到几点");
    }

    private boolean asksStoreHours(String text) {
        boolean storeContext = containsAny(text, "门店", "店里", "店铺", "到店");
        if (asksAgentHours(text) && !storeContext) return false;
        return containsAny(text, "营业时间", "几点营业", "几点开门", "几点关门", "营业到几点", "门店几点上班", "店里几点上班");
    }

    public List<Map<String, Object>> listAll() {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("b");
        return jdbc.queryForList(
                "SELECT b.fact_key, b.project, b.fact_type, b.display_text, b.status, b.source, b.valid_from, " +
                        "b.valid_until, b.reviewer, b.update_time FROM business_fact b" +
                        (scope.active() ? " WHERE " + scope.sql() : "") + " ORDER BY b.fact_type, b.fact_key",
                scope.args().toArray());
    }

    public void upsert(String factKey, String project, String factType, String displayText,
                       String status, String source, String reviewer,
                       LocalDateTime validFrom, LocalDateTime validUntil) {
        if (factKey == null || factKey.isBlank() || factType == null || factType.isBlank()) {
            throw new IllegalArgumentException("事实键和事实类型不能为空");
        }
        if (!List.of("draft", "published", "disabled").contains(status)) {
            throw new IllegalArgumentException("事实状态只能是 draft / published / disabled");
        }
        if ("published".equals(status) && (displayText == null || displayText.isBlank())) {
            throw new IllegalArgumentException("发布状态必须填写对客户展示的话术");
        }
        TenantExecutionContext.Scope runtime = TenantExecutionContext.current();
        Long tenantId = runtime == null ? null : runtime.tenantId();
        Long kfAccountId = runtime == null ? null : runtime.kfAccountId();
        jdbc.update(
                "INSERT INTO business_fact(tenant_id,kf_account_id,fact_key,project,fact_type,display_text,status,source,reviewer," +
                        "valid_from,valid_until,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,now()) " +
                        "ON CONFLICT (tenant_id,kf_account_id,fact_key) DO UPDATE SET " +
                        "project=EXCLUDED.project,fact_type=EXCLUDED.fact_type,display_text=EXCLUDED.display_text," +
                        "status=EXCLUDED.status,source=EXCLUDED.source,reviewer=EXCLUDED.reviewer," +
                        "valid_from=EXCLUDED.valid_from,valid_until=EXCLUDED.valid_until,update_time=now()",
                tenantId, kfAccountId, factKey, emptyToNull(project), factType, emptyToNull(displayText), status,
                emptyToNull(source), emptyToNull(reviewer), validFrom, validUntil);
    }

    private String publishedText(String key) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.display_text FROM business_fact b WHERE b.fact_key=? AND b.status='published' " +
                        "AND display_text IS NOT NULL AND display_text<>'' " +
                        "AND (valid_from IS NULL OR valid_from<=now()) " +
                        "AND (valid_until IS NULL OR valid_until>now())");
        List<Object> args = new ArrayList<>(List.of(key));
        ScopedDataAccess.appendAnd(sql, args, "b");
        sql.append(" ORDER BY b.tenant_id NULLS LAST, b.kf_account_id NULLS LAST LIMIT 1");
        List<String> rows = jdbc.queryForList(sql.toString(), String.class, args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 历史价格表只保留数字兼容能力，绝不返回其中的 sale_copy / brief_copy。 */
    private String priceFromArchivedKnowledge(String projectKey) {
        String content = findPriceContent(projectKey);
        if (content == null) return null;
        String rowProject = field(content, "project_key");
        String activityText = field(content, "activity_price");
        BigDecimal activity = money(activityText);
        if (activity == null) return null;
        BigDecimal original = money(field(content, "original_price"));
        BigDecimal deposit = money(field(content, "deposit"));
        return buildPriceReply(rowProject, activity, original, deposit);
    }

    private String priceFromOffer(String project, ProjectCatalogService.Offer offer) {
        BigDecimal activity = offer.activityPrice();
        BigDecimal original = offer.originalPrice();
        BigDecimal deposit = offer.deposit();
        String brief = offer.briefCopy();
        boolean briefMatches = brief != null && brief.contains(amount(activity) + "元")
                && (deposit == null || deposit.signum() == 0
                || (deposit.compareTo(activity) <= 0
                && brief.contains(amount(deposit) + "元")
                && brief.contains(amount(activity.subtract(deposit)) + "元")))
                && !containsAny(brief, "锁定名额", "锁定优惠", "限30个名额", "恢复原价",
                "无条件退款", "赠送接车", "额外赠送", "最先进", "从根源解决",
                "限时活动", "限量名额", "最后名额", "仅剩", "保证有效");
        return briefMatches ? brief.trim() : buildPriceReply(project, activity, original, deposit);
    }

    private String resolveProjectKey(String text, MessageUnderstanding.Result understanding) {
        if (projectCatalog != null) {
            String canonical = projectCatalog.canonicalName(text);
            if (canonical != null) return canonical;
        }
        if (text != null && text.contains("抗衰全脸")) return "抗衰全脸";
        return understanding == null ? null : understanding.project();
    }

    private boolean asksProjectScope(String text) {
        return containsAny(text, "哪些部位", "什么部位", "几个部位", "多少个部位", "服务范围", "操作范围")
                || (containsAny(text, "包含", "包括", "覆盖") && containsAny(text, "部位", "区域", "范围"));
    }

    private boolean asksDepositRefund(String text) {
        return containsAny(text, "订金能退", "定金能退", "退订金", "退定金", "订金退款", "定金退款")
                || (containsAny(text, "订金", "定金")
                && containsAny(text, "退吗", "能退", "可以退", "怎么退", "如何退", "不满意"));
    }

    private boolean asksActivityAvailability(String text) {
        return containsAny(text, "活动结束", "活动到期", "什么时候结束", "活动还有")
                || (text.contains("名额")
                && containsAny(text, "还有", "还剩", "剩余", "多少", "几个", "有吗", "结束", "到期"));
    }

    private String findPriceContent(String projectKey) {
        StringBuilder sql = new StringBuilder(
                "SELECT k.content FROM knowledge_chunk k LEFT JOIN knowledge_document d ON d.id=k.document_id " +
                        "WHERE lower(k.doc_name)=lower(?) AND k.status='published' " +
                        "AND COALESCE(d.status,'published')='published' " +
                        "AND (d.valid_from IS NULL OR d.valid_from<=now()) " +
                        "AND (d.valid_until IS NULL OR d.valid_until>now())");
        List<Object> args = new ArrayList<>(List.of("price.xlsx"));
        ScopedDataAccess.appendAnd(sql, args, "k");
        sql.append(" ORDER BY k.tenant_id NULLS LAST, k.kf_account_id NULLS LAST, k.chunk_index");
        List<String> chunks = jdbc.queryForList(sql.toString(), String.class, args.toArray());
        for (String content : chunks) {
            String rowProject = field(content, "project_key");
            if (samePriceProject(rowProject, projectKey)) return content;
        }
        return null;
    }

    private String buildPriceReply(String project, BigDecimal activity,
                                   BigDecimal original, BigDecimal deposit) {
        StringBuilder reply = new StringBuilder(project).append("活动价").append(amount(activity)).append("元");
        if (original != null && original.signum() > 0) {
            reply.append("（原价").append(amount(original)).append("元）");
        }
        if (deposit != null && deposit.signum() > 0 && deposit.compareTo(activity) <= 0) {
            reply.append("，").append(amount(deposit)).append("元订金可抵扣，到店再付")
                    .append(amount(activity.subtract(deposit))).append("元");
        }
        return reply.append("。").toString();
    }

    private boolean samePriceProject(String rowProject, String requested) {
        if (rowProject == null || requested == null) return false;
        if ("抗衰全脸".equals(requested)) return rowProject.contains("抗衰") && rowProject.contains("全脸");
        if ("抗衰".equals(requested) && rowProject.contains("全脸")) return false;
        return canonicalProject(rowProject).equals(canonicalProject(requested));
    }

    private String canonicalProject(String value) {
        if (value == null) return "";
        if (value.contains("抗衰") || value.contains("抗老")) return "抗衰";
        if (value.contains("眼袋")) return "眼袋";
        if (value.contains("淡斑") || value.contains("祛斑")) return "淡斑";
        if (value.contains("胡子") || value.contains("胡须")) return "胡子";
        return value.trim();
    }

    private String field(String content, String key) {
        if (content == null) return null;
        Matcher matcher = Pattern.compile("(?im)^\\s*-\\s*" + Pattern.quote(key)
                + "\\s*[：:]\\s*(.*?)\\s*$").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private BigDecimal money(String value) {
        if (value == null || value.isBlank()) return null;
        String number = value.replace(",", "").replaceAll("[^0-9.\\-]", "");
        try {
            BigDecimal parsed = new BigDecimal(number);
            return parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private boolean asksAboutMaleEligibility(String text) {
        return containsAny(text, "男的", "男性", "男士", "男人")
                && containsAny(text, "抗衰", "抗老", "淡斑", "祛斑")
                && containsAny(text, "能做", "可以做", "适合", "能不能", "可不可以");
    }

    private boolean asksAboutBeardSurcharge(String text) {
        return containsAny(text, "络腮胡", "络腮须")
                && containsAny(text, "加钱", "加价", "另外收费", "额外收费", "同价", "一个价");
    }

    private String appendSentence(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + (left.endsWith("。") || left.endsWith("！") || left.endsWith("？") ? "" : "。") + right;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
