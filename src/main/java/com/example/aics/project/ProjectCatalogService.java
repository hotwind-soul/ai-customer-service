package com.example.aics.project;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.config.ConfigService;
import com.example.aics.config.ScopedDataAccess;
import com.example.aics.knowledge.DocumentTextExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目、别名、价格、图片和门店的唯一主数据入口。
 * 旧 project/projects 字符串列只作为兼容投影，由本服务在同一事务内同步。
 */
@Service
public class ProjectCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProjectCatalogService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("draft", "published", "disabled");
    private static final List<String> BLOCKED_PUBLISHED_COPY_TERMS = List.of(
            "最先进", "从根源解决", "保证有效", "包好", "百分百", "永不反弹", "永久维持",
            "绝对安全", "零风险", "完全不痛", "无需停药", "不用停药", "无条件退款",
            "锁定名额", "锁定优惠", "赠送接车", "免费接车", "限时活动", "限量名额",
            "最后名额", "仅剩", "恢复原价", "一律不能享受活动价");

    private final JdbcTemplate jdbc;
    private final ConfigService config;

    public ProjectCatalogService(JdbcTemplate jdbc, ConfigService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public record Offer(BigDecimal activityPrice, BigDecimal originalPrice, BigDecimal deposit,
                        String briefCopy, String saleCopy, String source) {}

    public record OfferCommand(BigDecimal activityPrice, BigDecimal originalPrice, BigDecimal deposit,
                               String briefCopy, String saleCopy, String status) {}

    public record ProjectCommand(Long parentId, String code, String name, String serviceScope,
                                 String status, Integer sortOrder, List<String> aliases,
                                 OfferCommand offer, List<Long> imageIds, List<Long> storeIds) {
        /** 保留旧调用签名，已有测试和内部调用可逐步迁移。 */
        public ProjectCommand(Long parentId, String code, String name, String status, Integer sortOrder,
                              List<String> aliases, OfferCommand offer,
                              List<Long> imageIds, List<Long> storeIds) {
            this(parentId, code, name, null, status, sortOrder, aliases, offer, imageIds, storeIds);
        }
    }

    public List<Map<String, Object>> list() {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        String sql = "SELECT p.id,p.parent_id,parent.name AS parent_name,p.tenant_id,p.kf_account_id," +
                "p.code,p.name,p.service_scope,p.status,p.sort_order,p.create_time,p.update_time FROM service_project p " +
                "LEFT JOIN service_project parent ON parent.id=p.parent_id" +
                (scope.active() ? " WHERE " + scope.sql() : "") +
                " ORDER BY COALESCE(parent.sort_order,p.sort_order),COALESCE(parent.id,p.id)," +
                "CASE WHEN p.parent_id IS NULL THEN 0 ELSE 1 END,p.sort_order,p.id";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, scope.args().toArray());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) result.add(details(row));
        return List.copyOf(result);
    }

    public List<String> publishedNames() {
        List<Map<String, Object>> rows = scopedPublishedProjects(true);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        rows.forEach(row -> names.add(text(row.get("name"))));
        names.remove("");
        return List.copyOf(names);
    }

    /** 后台下拉使用管理员数据范围；不能复用客户运行时查询，否则会把租户项目暴露给网页访客。 */
    public List<String> adminPublishedNames() {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        String sql = "SELECT p.name FROM service_project p WHERE p.status='published'" +
                (scope.active() ? " AND " + scope.sql() : "") +
                " ORDER BY p.tenant_id NULLS FIRST,p.kf_account_id NULLS FIRST,p.sort_order,p.id";
        LinkedHashSet<String> names = new LinkedHashSet<>(
                jdbc.queryForList(sql, String.class, scope.args().toArray()));
        names.removeIf(name -> name == null || name.isBlank());
        return List.copyOf(names);
    }

    /** 门店编辑使用：只返回一级主项目（parent_id IS NULL），子项目默认跟随主项目。 */
    public List<String> adminRootPublishedNames() {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        String sql = "SELECT p.name FROM service_project p WHERE p.status='published' AND p.parent_id IS NULL" +
                (scope.active() ? " AND " + scope.sql() : "") +
                " ORDER BY p.tenant_id NULLS FIRST,p.kf_account_id NULLS FIRST,p.sort_order,p.id";
        LinkedHashSet<String> names = new LinkedHashSet<>(
                jdbc.queryForList(sql, String.class, scope.args().toArray()));
        names.removeIf(name -> name == null || name.isBlank());
        return List.copyOf(names);
    }

    public Map<String, Object> resourceOptions() {
        ScopedDataAccess.Condition imageScope = ScopedDataAccess.condition("i");
        ScopedDataAccess.Condition storeScope = ScopedDataAccess.condition("s");
        List<Map<String, Object>> images = jdbc.queryForList(
                "SELECT i.id,i.name,i.asset_type,i.enabled,i.url FROM image_asset i" +
                        (imageScope.active() ? " WHERE " + imageScope.sql() : "") + " ORDER BY i.id DESC",
                imageScope.args().toArray());
        List<Map<String, Object>> stores = jdbc.queryForList(
                "SELECT s.id,s.city,s.district,s.name,s.address FROM store s" +
                        (storeScope.active() ? " WHERE " + storeScope.sql() : "") +
                        " ORDER BY s.city,s.district,s.name,s.id",
                storeScope.args().toArray());
        return Map.of("images", images, "stores", stores);
    }

    /** 当前租户可用的标准名和别名；租户/客服号项目优先于全局同名项目。 */
    public Map<String, List<String>> projectRules() {
        LinkedHashMap<String, LinkedHashSet<String>> rules = new LinkedHashMap<>();
        for (Map<String, Object> project : scopedPublishedProjects(false)) {
            long id = number(project.get("id"));
            String name = text(project.get("name"));
            LinkedHashSet<String> aliases = rules.computeIfAbsent(name, ignored -> new LinkedHashSet<>());
            aliases.add(name);
            aliases.addAll(jdbc.queryForList(
                    "SELECT alias FROM service_project_alias WHERE project_id=? ORDER BY length(alias) DESC,alias",
                    String.class, id));
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        rules.forEach((name, aliases) -> result.put(name, List.copyOf(aliases)));
        return result;
    }

    public String canonicalName(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = normalize(value);
        record Candidate(String name, String alias) {}
        List<Candidate> candidates = new ArrayList<>();
        projectRules().forEach((name, aliases) -> aliases.forEach(alias ->
                candidates.add(new Candidate(name, normalize(alias)))));
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.alias().length()).reversed());
        for (Candidate candidate : candidates) {
            if (normalized.equals(candidate.alias())) return candidate.name();
        }
        for (Candidate candidate : candidates) {
            if (!candidate.alias().isBlank() && normalized.contains(candidate.alias())) return candidate.name();
        }
        return null;
    }

    public Offer publishedOffer(String projectName) {
        if (projectName == null || projectName.isBlank()) return null;
        String exact = projectName.trim();
        Offer offer = queryOffer(exact);
        if (offer != null) return offer;
        String canonical = canonicalName(exact);
        return canonical == null || canonical.equals(exact) ? null : queryOffer(canonical);
    }

    /** 客户可见的项目服务范围；未维护时返回 null，禁止从旧知识或父项目推断。 */
    public String publishedServiceScope(String projectName) {
        if (projectName == null || projectName.isBlank()) return null;
        String exact = projectName.trim();
        String scope = queryServiceScope(exact);
        if (scope != null) return scope;
        String canonical = canonicalName(exact);
        return canonical == null || canonical.equals(exact) ? null : queryServiceScope(canonical);
    }

    @Transactional
    public long create(ProjectCommand command) {
        ProjectCommand valid = validate(command);
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long accountId = scope == null ? null : scope.kfAccountId();
        validateParent(valid.parentId(), null, tenantId, accountId);
        String code = valid.code() == null || valid.code().isBlank()
                ? generatedCode(valid.name()) : valid.code().trim();
        Long id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO service_project(parent_id,tenant_id,kf_account_id,code,name,service_scope,status,sort_order) " +
                            "VALUES (?,?,?,?,?,?,?,?) RETURNING id", Long.class,
                    valid.parentId(), tenantId, accountId, code, valid.name().trim(),
                    blankToNull(valid.serviceScope()), valid.status(), valid.sortOrder());
        } catch (DataIntegrityViolationException e) {
            throw duplicateProject(e);
        }
        if (id == null) throw new IllegalStateException("项目创建失败");
        saveContents(id, null, valid);
        syncDerivedConfig();
        return id;
    }

    @Transactional
    public void update(long id, ProjectCommand command) {
        ProjectCommand valid = validate(command);
        Map<String, Object> existing = findEditable(id);
        if (existing == null) throw new IllegalArgumentException("项目不存在或无权操作");
        validateParent(valid.parentId(), id, nullableLong(existing.get("tenant_id")),
                nullableLong(existing.get("kf_account_id")));
        String oldName = text(existing.get("name"));
        String code = valid.code() == null || valid.code().isBlank()
                ? text(existing.get("code")) : valid.code().trim();
        int changed;
        try {
            changed = jdbc.update(
                    "UPDATE service_project SET parent_id=?,code=?,name=?,service_scope=?,status=?,sort_order=?,update_time=now() " +
                            "WHERE id=?",
                    valid.parentId(), code, valid.name().trim(), blankToNull(valid.serviceScope()),
                    valid.status(), valid.sortOrder(), id);
        } catch (DataIntegrityViolationException e) {
            throw duplicateProject(e);
        }
        if (changed == 0) throw new IllegalArgumentException("项目不存在或无权操作");
        saveContents(id, oldName, valid);
        if ("disabled".equals(valid.status())) disableChildren(id);
        syncDerivedConfig();
    }

    @Transactional
    public void disable(long id) {
        Map<String, Object> existing = findEditable(id);
        if (existing == null) throw new IllegalArgumentException("项目不存在或无权操作");
        List<Long> affectedIds = hierarchyIds(id);
        jdbc.update("UPDATE service_project SET status='disabled',update_time=now() WHERE id=? OR parent_id=?", id, id);
        jdbc.update("UPDATE project_offer SET status='disabled',update_time=now() " +
                "WHERE project_id IN (SELECT id FROM service_project WHERE id=? OR parent_id=?)", id, id);
        affectedIds.forEach(this::refreshLegacyImages);
        affectedIds.forEach(this::refreshLegacyStores);
        syncDerivedConfig();
    }

    /**
     * price.xlsx 只做批量导入：解析后写结构化报价，并以 NULL embedding 留档，
     * 不再调用向量模型，也不会进入 RAG 候选。
     */
    @Transactional
    public int importPriceRows(String docName, List<DocumentTextExtractor.PriceRow> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("价格表没有有效项目");
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long accountId = scope == null ? null : scope.kfAccountId();

        Long documentId = jdbc.queryForObject(
                "INSERT INTO knowledge_document(tenant_id,kf_account_id,doc_name,doc_type,status) " +
                        "VALUES (?,?,?,'structured','published') " +
                        "ON CONFLICT (tenant_id,kf_account_id,doc_name) DO UPDATE SET " +
                        "doc_type='structured',status='published',version=knowledge_document.version+1," +
                        "update_time=now() RETURNING id",
                Long.class, tenantId, accountId, docName);
        jdbc.update("DELETE FROM knowledge_chunk WHERE doc_name=? AND tenant_id IS NOT DISTINCT FROM ? " +
                "AND kf_account_id IS NOT DISTINCT FROM ?", docName, tenantId, accountId);
        jdbc.update("UPDATE project_offer o SET status='disabled',update_time=now() FROM service_project p " +
                        "WHERE o.project_id=p.id AND o.source='price.xlsx' " +
                        "AND p.tenant_id IS NOT DISTINCT FROM ? AND p.kf_account_id IS NOT DISTINCT FROM ?",
                tenantId, accountId);

        int index = 0;
        boolean createdProject = false;
        for (DocumentTextExtractor.PriceRow row : rows) {
            Long projectId = exactProjectId(row.projectKey(), tenantId, accountId);
            if (projectId == null) {
                projectId = createImportedProject(row.projectKey(), tenantId, accountId);
                createdProject = true;
            }
            BigDecimal activity = money(field(row.content(), "activity_price"), true);
            BigDecimal original = money(field(row.content(), "original_price"), false);
            BigDecimal deposit = money(field(row.content(), "deposit"), false);
            validateOffer(activity, original, deposit);
            String brief = field(row.content(), "brief_copy");
            String sale = blockField(row.content(), "sale_copy");
            assertPublishedOfferCopySafe(brief);
            assertPublishedOfferCopySafe(sale);
            Set<Long> stalePromoIds = currentPromoIds(projectId);
            boolean priceChanged = offerPriceChanged(projectId, activity, original, deposit);
            jdbc.update("INSERT INTO project_offer(project_id,offer_key,activity_price,original_price,deposit," +
                            "brief_copy,sale_copy,status,source,update_time) VALUES (?,'default',?,?,?,?,?,'published','price.xlsx',now()) " +
                            "ON CONFLICT (project_id,offer_key) DO UPDATE SET activity_price=EXCLUDED.activity_price," +
                            "original_price=EXCLUDED.original_price,deposit=EXCLUDED.deposit,brief_copy=EXCLUDED.brief_copy," +
                            "sale_copy=EXCLUDED.sale_copy,status='published',source='price.xlsx',update_time=now()",
                    projectId, activity, original, deposit, blankToNull(brief), blankToNull(sale));
            if (priceChanged) unlinkPromoAssets(projectId, stalePromoIds);
            jdbc.update("INSERT INTO knowledge_chunk(document_id,tenant_id,kf_account_id,doc_name,chunk_index," +
                            "content,search_text,project_id,project,category,knowledge_type,risk_level,safe_direct,status," +
                            "embedding_strategy,embedding) VALUES (?,?,?,?,?,?,?,?,?,'价格','offer','unreviewed',false," +
                            "'published','structured-offer-v1',NULL)",
                    documentId, tenantId, accountId, docName, index++, row.content(), row.searchText(),
                    projectId, row.projectKey());
        }
        if (createdProject) syncDerivedConfig();
        return rows.size();
    }

    /** 首次升级时把历史 price.xlsx 切片灌入结构化报价；已有项目中心报价绝不覆盖。 */
    @PostConstruct
    void migrateLegacyOffers() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT k.id,k.tenant_id,k.kf_account_id,k.project,k.content FROM knowledge_chunk k " +
                            "WHERE lower(k.doc_name)=lower('price.xlsx') ORDER BY k.id");
            int migrated = 0;
            for (Map<String, Object> row : rows) {
                String content = text(row.get("content"));
                String projectName = firstNonBlank(field(content, "project_key"), text(row.get("project")));
                if (projectName.isBlank()) continue;
                Long tenantId = nullableLong(row.get("tenant_id"));
                Long accountId = nullableLong(row.get("kf_account_id"));
                Long projectId = exactProjectId(projectName, tenantId, accountId);
                if (projectId == null) projectId = createImportedProject(projectName, tenantId, accountId);
                BigDecimal activity = money(field(content, "activity_price"), false);
                if (activity == null) continue;
                int n = jdbc.update("INSERT INTO project_offer(project_id,offer_key,activity_price,original_price,deposit," +
                                "brief_copy,sale_copy,status,source) VALUES (?,'default',?,?,?,?,?,'published','price.xlsx') " +
                                "ON CONFLICT (project_id,offer_key) DO NOTHING",
                        projectId, activity, money(field(content, "original_price"), false),
                        money(field(content, "deposit"), false), blankToNull(field(content, "brief_copy")),
                        blankToNull(blockField(content, "sale_copy")));
                migrated += n;
            }
            if (!rows.isEmpty()) {
                jdbc.update("UPDATE knowledge_document SET doc_type='structured',update_time=now() " +
                        "WHERE lower(doc_name)=lower('price.xlsx')");
                jdbc.update("UPDATE knowledge_chunk SET embedding=NULL,embedding_strategy='structured-offer-v1'," +
                        "knowledge_type='offer',update_time=now() WHERE lower(doc_name)=lower('price.xlsx')");
            }
            if (migrated > 0) {
                syncDerivedConfig();
                log.info("已将 {} 条历史价格迁移到项目中心", migrated);
            }
        } catch (RuntimeException e) {
            // 历史价格迁移失败不能阻止客服启动；旧 price.xlsx 读取链路仍保留兜底。
            log.warn("历史价格迁移到项目中心失败，将继续使用兼容读取: {}", e.getMessage());
        }
    }

    private Map<String, Object> details(Map<String, Object> row) {
        long id = number(row.get("id"));
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("tenantId", row.get("tenant_id"));
        result.put("kfAccountId", row.get("kf_account_id"));
        result.put("parentId", row.get("parent_id"));
        result.put("parentName", row.get("parent_name"));
        result.put("code", row.get("code"));
        result.put("name", row.get("name"));
        result.put("serviceScope", row.get("service_scope"));
        result.put("status", row.get("status"));
        result.put("sortOrder", row.get("sort_order"));
        result.put("aliases", jdbc.queryForList(
                "SELECT alias FROM service_project_alias WHERE project_id=? ORDER BY length(alias) DESC,alias",
                String.class, id));
        List<Map<String, Object>> offers = jdbc.queryForList(
                "SELECT activity_price,original_price,deposit,brief_copy,sale_copy,status,source " +
                        "FROM project_offer WHERE project_id=? AND offer_key='default'", id);
        result.put("offer", offers.isEmpty() ? null : camelOffer(offers.get(0)));
        result.put("imageIds", jdbc.queryForList(
                "SELECT image_id FROM project_asset WHERE project_id=? ORDER BY sort_order,image_id", Long.class, id));
        result.put("storeIds", jdbc.queryForList(
                "SELECT store_id FROM store_project WHERE project_id=? ORDER BY store_id", Long.class, id));
        result.put("updateTime", row.get("update_time"));
        return result;
    }

    private Map<String, Object> camelOffer(Map<String, Object> row) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("activityPrice", row.get("activity_price"));
        result.put("originalPrice", row.get("original_price"));
        result.put("deposit", row.get("deposit"));
        result.put("briefCopy", row.get("brief_copy"));
        result.put("saleCopy", row.get("sale_copy"));
        result.put("status", row.get("status"));
        result.put("source", row.get("source"));
        return result;
    }

    private List<Map<String, Object>> scopedPublishedProjects(boolean rootOnly) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        boolean runtimeScoped = TenantExecutionContext.current() != null;
        String sql = "SELECT p.id,p.parent_id,p.name,p.tenant_id,p.kf_account_id,p.sort_order " +
                "FROM service_project p LEFT JOIN service_project parent ON parent.id=p.parent_id " +
                "WHERE p.status='published' " +
                "AND (p.parent_id IS NULL OR parent.status='published')" +
                (rootOnly ? " AND p.parent_id IS NULL" : "") +
                (!runtimeScoped ? " AND p.tenant_id IS NULL AND p.kf_account_id IS NULL" : "") +
                (scope.active() ? " AND " + scope.sql() : "") +
                " ORDER BY p.tenant_id NULLS LAST,p.kf_account_id NULLS LAST," +
                "COALESCE(parent.sort_order,p.sort_order),COALESCE(parent.id,p.id)," +
                "CASE WHEN p.parent_id IS NULL THEN 0 ELSE 1 END,p.sort_order,p.id";
        return jdbc.queryForList(sql, scope.args().toArray());
    }

    private Offer queryOffer(String projectName) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        boolean runtimeScoped = TenantExecutionContext.current() != null;
        String sql = "SELECT o.activity_price,o.original_price,o.deposit,o.brief_copy,o.sale_copy,o.source " +
                "FROM project_offer o JOIN service_project p ON p.id=o.project_id " +
                "LEFT JOIN service_project parent ON parent.id=p.parent_id " +
                "WHERE p.status='published' AND o.status='published' " +
                "AND (p.parent_id IS NULL OR parent.status='published') AND p.name=? " +
                (!runtimeScoped ? " AND p.tenant_id IS NULL AND p.kf_account_id IS NULL" : "") +
                (scope.active() ? " AND " + scope.sql() : "") +
                " ORDER BY p.tenant_id NULLS LAST,p.kf_account_id NULLS LAST,o.update_time DESC LIMIT 1";
        List<Object> args = new ArrayList<>();
        args.add(projectName);
        args.addAll(scope.args());
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Offer(decimal(row.get("activity_price")), decimal(row.get("original_price")),
                decimal(row.get("deposit")), textOrNull(row.get("brief_copy")),
                textOrNull(row.get("sale_copy")), textOrNull(row.get("source")));
    }

    private String queryServiceScope(String projectName) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        boolean runtimeScoped = TenantExecutionContext.current() != null;
        String sql = "SELECT p.service_scope FROM service_project p " +
                "LEFT JOIN service_project parent ON parent.id=p.parent_id " +
                "WHERE p.status='published' AND (p.parent_id IS NULL OR parent.status='published') " +
                "AND p.name=? AND p.service_scope IS NOT NULL AND btrim(p.service_scope)<>'' " +
                (!runtimeScoped ? "AND p.tenant_id IS NULL AND p.kf_account_id IS NULL " : "") +
                (scope.active() ? "AND " + scope.sql() + " " : "") +
                "ORDER BY p.tenant_id NULLS LAST,p.kf_account_id NULLS LAST,p.update_time DESC LIMIT 1";
        List<Object> args = new ArrayList<>();
        args.add(projectName);
        args.addAll(scope.args());
        List<String> rows = jdbc.queryForList(sql, String.class, args.toArray());
        return rows.isEmpty() ? null : textOrNull(rows.get(0));
    }

    private ProjectCommand validate(ProjectCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        String status = command.status() == null || command.status().isBlank() ? "draft" : command.status();
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("项目状态不正确");
        int order = command.sortOrder() == null ? 100 : command.sortOrder();
        if (order < 0 || order > 100000) throw new IllegalArgumentException("项目排序必须在 0 到 100000 之间");
        if (command.code() != null && !command.code().isBlank()
                && !command.code().trim().matches("[A-Za-z0-9._-]{2,80}")) {
            throw new IllegalArgumentException("项目编码只能包含字母、数字、点、下划线和短横线");
        }
        String serviceScope = command.serviceScope() == null ? null : command.serviceScope().trim();
        if (serviceScope != null && serviceScope.length() > 2000) {
            throw new IllegalArgumentException("项目服务范围不能超过 2000 个字符");
        }
        if ("published".equals(status)) assertPublishedOfferCopySafe(serviceScope);
        OfferCommand offer = command.offer();
        if (offer != null) {
            validateOffer(offer.activityPrice(), offer.originalPrice(), offer.deposit());
            String offerStatus = offer.status() == null || offer.status().isBlank() ? "draft" : offer.status();
            if (!STATUSES.contains(offerStatus)) throw new IllegalArgumentException("报价状态不正确");
            if ("published".equals(offerStatus)) {
                assertPublishedOfferCopySafe(offer.briefCopy());
                assertPublishedOfferCopySafe(offer.saleCopy());
            }
            offer = new OfferCommand(offer.activityPrice(), offer.originalPrice(), offer.deposit(),
                    offer.briefCopy(), offer.saleCopy(), offerStatus);
        }
        return new ProjectCommand(command.parentId(), command.code(), command.name().trim(), serviceScope,
                status, order, command.aliases(), offer, command.imageIds(), command.storeIds());
    }

    private void validateParent(Long parentId, Long projectId, Long tenantId, Long accountId) {
        if (parentId == null) return;
        if (parentId <= 0) throw new IllegalArgumentException("父项目不正确");
        if (projectId != null && parentId.equals(projectId)) {
            throw new IllegalArgumentException("项目不能归属于自身");
        }
        List<Map<String, Object>> parents = jdbc.queryForList(
                "SELECT id,parent_id,tenant_id,kf_account_id FROM service_project WHERE id=?", parentId);
        if (parents.isEmpty()) throw new IllegalArgumentException("父项目不存在");
        Map<String, Object> parent = parents.get(0);
        if (parent.get("parent_id") != null) {
            throw new IllegalArgumentException("仅支持一级子项目，不能选择子项目作为父项目");
        }
        if (!Objects.equals(nullableLong(parent.get("tenant_id")), tenantId)
                || !Objects.equals(nullableLong(parent.get("kf_account_id")), accountId)) {
            throw new IllegalArgumentException("父项目必须与子项目属于同一企业和客服号");
        }
        if (projectId != null) {
            Integer children = jdbc.queryForObject(
                    "SELECT count(*) FROM service_project WHERE parent_id=?", Integer.class, projectId);
            if (children != null && children > 0) {
                throw new IllegalArgumentException("已有子项目的项目不能再归属于其他项目");
            }
        }
    }

    private void validateOffer(BigDecimal activity, BigDecimal original, BigDecimal deposit) {
        for (BigDecimal value : List.of(activity == null ? BigDecimal.ZERO : activity,
                original == null ? BigDecimal.ZERO : original, deposit == null ? BigDecimal.ZERO : deposit)) {
            if (value.signum() < 0) throw new IllegalArgumentException("价格不能小于 0");
        }
        if (deposit != null && activity != null && deposit.compareTo(activity) > 0) {
            throw new IllegalArgumentException("订金不能大于活动价");
        }
    }

    private void saveContents(long id, String oldName, ProjectCommand command) {
        replaceAliases(id, command.name(), command.aliases());
        Set<Long> stalePromoIds = currentPromoIds(id);
        boolean priceChanged = saveOffer(id, command.offer());
        if (oldName != null && !oldName.equals(command.name())) {
            jdbc.update("UPDATE knowledge_chunk SET project=?,update_time=now() WHERE project_id=?",
                    command.name(), id);
            jdbc.update("UPDATE business_fact b SET project=?,update_time=now() WHERE project=? " +
                            "AND EXISTS (SELECT 1 FROM service_project p WHERE p.id=? " +
                            "AND b.tenant_id IS NOT DISTINCT FROM p.tenant_id " +
                            "AND b.kf_account_id IS NOT DISTINCT FROM p.kf_account_id)",
                    command.name(), oldName, id);
        }
        if (command.imageIds() != null) {
            List<Long> requested = command.imageIds();
            if (priceChanged && !stalePromoIds.isEmpty()) {
                requested = requested.stream().filter(imageId -> !stalePromoIds.contains(imageId)).toList();
            }
            replaceImages(id, requested);
        } else if (priceChanged) {
            unlinkPromoAssets(id, stalePromoIds);
        }
        if (command.storeIds() != null) replaceStores(id, command.storeIds());
        refreshLegacyImages(id);
        refreshLegacyStores(id);
    }

    private void replaceAliases(long projectId, String name, Collection<String> aliases) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(name.trim());
        if (aliases != null) aliases.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).forEach(values::add);
        if (values.size() > 100) throw new IllegalArgumentException("单个项目最多维护 100 个别名");
        jdbc.update("DELETE FROM service_project_alias WHERE project_id=?", projectId);
        values.forEach(alias -> jdbc.update(
                "INSERT INTO service_project_alias(project_id,alias) VALUES (?,?)", projectId, alias));
    }

    private boolean saveOffer(long projectId, OfferCommand offer) {
        if (offer == null) return false;
        boolean priceChanged = offerPriceChanged(projectId, offer.activityPrice(),
                offer.originalPrice(), offer.deposit());
        jdbc.update("INSERT INTO project_offer(project_id,offer_key,activity_price,original_price,deposit," +
                        "brief_copy,sale_copy,status,source,update_time) " +
                        "VALUES (?,'default',?,?,?,?,?,?,'project-center',now()) " +
                        "ON CONFLICT (project_id,offer_key) DO UPDATE SET activity_price=EXCLUDED.activity_price," +
                        "original_price=EXCLUDED.original_price,deposit=EXCLUDED.deposit,brief_copy=EXCLUDED.brief_copy," +
                        "sale_copy=EXCLUDED.sale_copy,status=EXCLUDED.status,source='project-center',update_time=now()",
                projectId, offer.activityPrice(), offer.originalPrice(), offer.deposit(),
                blankToNull(offer.briefCopy()), blankToNull(offer.saleCopy()), offer.status());
        return priceChanged;
    }

    private boolean offerPriceChanged(long projectId, BigDecimal activity, BigDecimal original,
                                      BigDecimal deposit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT activity_price,original_price,deposit FROM project_offer " +
                        "WHERE project_id=? AND offer_key='default'", projectId);
        if (rows.isEmpty()) return true;
        Map<String, Object> row = rows.get(0);
        return !sameMoney(decimal(row.get("activity_price")), activity)
                || !sameMoney(decimal(row.get("original_price")), original)
                || !sameMoney(decimal(row.get("deposit")), deposit);
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private Set<Long> currentPromoIds(long projectId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT pa.image_id FROM project_asset pa JOIN image_asset i ON i.id=pa.image_id " +
                        "WHERE pa.project_id=? AND i.asset_type='promo'", Long.class, projectId));
    }

    private void unlinkPromoAssets(long projectId, Set<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) return;
        jdbc.update("DELETE FROM project_asset WHERE project_id=? AND image_id IN (" +
                String.join(",", java.util.Collections.nCopies(imageIds.size(), "?")) + ")",
                joinedArgs(projectId, imageIds));
        imageIds.forEach(this::refreshLegacyImage);
    }

    private Object[] joinedArgs(long projectId, Collection<Long> imageIds) {
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(imageIds);
        return args.toArray();
    }

    private void assertPublishedOfferCopySafe(String copy) {
        if (copy == null || copy.isBlank()) return;
        String hit = BLOCKED_PUBLISHED_COPY_TERMS.stream().filter(copy::contains).findFirst().orElse(null);
        if (hit != null) {
            throw new IllegalArgumentException("已发布销售话术包含不允许的承诺：“" + hit + "”，请修改后再发布");
        }
    }

    private void replaceImages(long projectId, List<Long> requested) {
        List<Long> oldIds = jdbc.queryForList(
                "SELECT image_id FROM project_asset WHERE project_id=?", Long.class, projectId);
        LinkedHashSet<Long> ids = cleanIds(requested);
        for (Long imageId : ids) ensureCompatibleResource(projectId, "image_asset", "i", imageId, "图片素材");
        jdbc.update("DELETE FROM project_asset WHERE project_id=?", projectId);
        int order = 0;
        for (Long imageId : ids) {
            jdbc.update("INSERT INTO project_asset(project_id,image_id,asset_role,sort_order) " +
                            "SELECT ?,i.id,COALESCE(NULLIF(i.asset_type,''),'effect'),? FROM image_asset i WHERE i.id=?",
                    projectId, order++, imageId);
        }
        LinkedHashSet<Long> affected = new LinkedHashSet<>(oldIds);
        affected.addAll(ids);
        affected.forEach(this::refreshLegacyImage);
    }

    private void replaceStores(long projectId, List<Long> requested) {
        List<Long> oldIds = jdbc.queryForList(
                "SELECT store_id FROM store_project WHERE project_id=?", Long.class, projectId);
        LinkedHashSet<Long> ids = cleanIds(requested);
        for (Long storeId : ids) ensureCompatibleResource(projectId, "store", "s", storeId, "门店");
        jdbc.update("DELETE FROM store_project WHERE project_id=?", projectId);
        ids.forEach(storeId -> jdbc.update(
                "INSERT INTO store_project(store_id,project_id) VALUES (?,?)", storeId, projectId));
        LinkedHashSet<Long> affected = new LinkedHashSet<>(oldIds);
        affected.addAll(ids);
        affected.forEach(this::refreshLegacyStore);
    }

    private void ensureCompatibleResource(long projectId, String table, String alias, long id, String label) {
        String sql = "SELECT count(*) FROM " + table + " " + alias +
                " JOIN service_project p ON p.id=? WHERE " + alias + ".id=? AND (" +
                "(p.tenant_id IS NULL AND p.kf_account_id IS NULL AND " + alias +
                ".tenant_id IS NULL AND " + alias + ".kf_account_id IS NULL) OR " +
                "(p.tenant_id IS NOT NULL AND " + alias + ".tenant_id IS NULL AND " + alias +
                ".kf_account_id IS NULL) OR " +
                "(p.tenant_id IS NOT NULL AND " + alias + ".tenant_id=p.tenant_id AND ((" +
                "p.kf_account_id IS NULL AND " + alias + ".kf_account_id IS NULL) OR (" +
                "p.kf_account_id IS NOT NULL AND (" + alias + ".kf_account_id IS NULL OR " + alias +
                ".kf_account_id=p.kf_account_id))))" +
                ")";
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, id);
        if (count == null || count == 0) throw new IllegalArgumentException(label + "不存在或无权关联：" + id);
    }

    private void refreshLegacyImages(long projectId) {
        jdbc.queryForList("SELECT image_id FROM project_asset WHERE project_id=?", Long.class, projectId)
                .forEach(this::refreshLegacyImage);
    }

    private void refreshLegacyImage(long imageId) {
        jdbc.update("UPDATE image_asset i SET project=(SELECT p.name FROM project_asset pa " +
                "JOIN service_project p ON p.id=pa.project_id WHERE pa.image_id=i.id " +
                "AND p.status<>'disabled' ORDER BY pa.sort_order,p.sort_order,p.id LIMIT 1) WHERE i.id=?", imageId);
    }

    private void refreshLegacyStores(long projectId) {
        jdbc.queryForList("SELECT store_id FROM store_project WHERE project_id=?", Long.class, projectId)
                .forEach(this::refreshLegacyStore);
    }

    private List<Long> hierarchyIds(long projectId) {
        return jdbc.queryForList(
                "SELECT id FROM service_project WHERE id=? OR parent_id=? ORDER BY id",
                Long.class, projectId, projectId);
    }

    private void disableChildren(long projectId) {
        List<Long> childIds = jdbc.queryForList(
                "SELECT id FROM service_project WHERE parent_id=? ORDER BY id", Long.class, projectId);
        if (childIds.isEmpty()) return;
        jdbc.update("UPDATE service_project SET status='disabled',update_time=now() WHERE parent_id=?", projectId);
        jdbc.update("UPDATE project_offer SET status='disabled',update_time=now() " +
                "WHERE project_id IN (SELECT id FROM service_project WHERE parent_id=?)", projectId);
        childIds.forEach(this::refreshLegacyImages);
        childIds.forEach(this::refreshLegacyStores);
    }

    private void refreshLegacyStore(long storeId) {
        jdbc.update("UPDATE store s SET projects=(SELECT string_agg(p.name,',' ORDER BY p.sort_order,p.id) " +
                "FROM store_project sp JOIN service_project p ON p.id=sp.project_id " +
                "WHERE sp.store_id=s.id AND p.status<>'disabled') WHERE s.id=?", storeId);
    }

    private Map<String, Object> findEditable(long id) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("p");
        String sql = "SELECT p.id,p.parent_id,p.code,p.name,p.tenant_id,p.kf_account_id " +
                "FROM service_project p WHERE p.id=?" +
                (scope.active() ? " AND " + scope.sql() : "");
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(scope.args());
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long exactProjectId(String name, Long tenantId, Long accountId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM service_project WHERE tenant_id IS NOT DISTINCT FROM ? " +
                        "AND kf_account_id IS NOT DISTINCT FROM ? AND name=? ORDER BY id LIMIT 1",
                Long.class, tenantId, accountId, name == null ? "" : name.trim());
        if (!rows.isEmpty()) return rows.get(0);
        rows = jdbc.queryForList(
                "SELECT p.id FROM service_project p JOIN service_project_alias a ON a.project_id=p.id " +
                        "WHERE p.tenant_id IS NOT DISTINCT FROM ? AND p.kf_account_id IS NOT DISTINCT FROM ? " +
                        "AND a.alias=? ORDER BY p.id LIMIT 1",
                Long.class, tenantId, accountId, name == null ? "" : name.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long createImportedProject(String name, Long tenantId, Long accountId) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isBlank()) throw new IllegalArgumentException("价格表项目名称不能为空");
        Long id = jdbc.queryForObject(
                "INSERT INTO service_project(tenant_id,kf_account_id,code,name,status,sort_order) " +
                        "VALUES (?,?,?,?, 'published',100) " +
                        "ON CONFLICT (tenant_id,kf_account_id,name) DO UPDATE SET update_time=now() RETURNING id",
                Long.class, tenantId, accountId, generatedCode(cleanName), cleanName);
        jdbc.update("INSERT INTO service_project_alias(project_id,alias) VALUES (?,?) ON CONFLICT DO NOTHING",
                id, cleanName);
        return id;
    }

    private void syncDerivedConfig() {
        List<Map<String, Object>> projects = jdbc.queryForList(
                "SELECT p.id,p.name,p.parent_id FROM service_project p " +
                        "LEFT JOIN service_project parent ON parent.id=p.parent_id " +
                        "WHERE p.status='published' AND (p.parent_id IS NULL OR parent.status='published') " +
                        "AND p.tenant_id IS NULL AND p.kf_account_id IS NULL " +
                        "ORDER BY COALESCE(parent.sort_order,p.sort_order),COALESCE(parent.id,p.id)," +
                        "CASE WHEN p.parent_id IS NULL THEN 0 ELSE 1 END,p.sort_order,p.id");
        LinkedHashMap<String, LinkedHashSet<String>> rules = new LinkedHashMap<>();
        for (Map<String, Object> project : projects) {
            long id = number(project.get("id"));
            String name = text(project.get("name"));
            LinkedHashSet<String> aliases = rules.computeIfAbsent(name, ignored -> new LinkedHashSet<>());
            aliases.add(name);
            aliases.addAll(jdbc.queryForList(
                    "SELECT alias FROM service_project_alias WHERE project_id=? ORDER BY length(alias) DESC,alias",
                    String.class, id));
        }
        LinkedHashMap<String, List<String>> serializable = new LinkedHashMap<>();
        rules.forEach((name, aliases) -> serializable.put(name, List.copyOf(aliases)));
        List<String> homeProjects = projects.stream()
                .filter(project -> project.get("parent_id") == null)
                .map(project -> text(project.get("name")))
                .filter(name -> !name.isBlank())
                .toList();
        try {
            config.setAll(Map.of(
                    "intent.projects", JSON.writeValueAsString(serializable),
                    "home.projectList", String.join("\n", homeProjects)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("项目别名序列化失败", e);
        }
    }

    private LinkedHashSet<Long> cleanIds(List<Long> values) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(Objects::nonNull).filter(id -> id > 0).forEach(result::add);
        return result;
    }

    private BigDecimal money(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("价格表缺少活动价");
            return null;
        }
        try {
            String normalized = value.replace(",", "").replace("￥", "").replace("¥", "")
                    .replace("元", "").trim();
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("价格格式不正确：" + value);
        }
    }

    private String field(String content, String key) {
        if (content == null) return null;
        Matcher matcher = Pattern.compile("(?im)^\\s*-\\s*" + Pattern.quote(key)
                + "\\s*[：:]\\s*(.*?)\\s*$").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String blockField(String content, String key) {
        if (content == null) return null;
        Matcher matcher = Pattern.compile("(?ims)^\\s*-\\s*" + Pattern.quote(key)
                + "\\s*[：:]\\s*(.*?)(?=^\\s*-\\s*[A-Za-z_\\u4e00-\\u9fa5]+\\s*[：:]|\\z)")
                .matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String generatedCode(String name) {
        return "project-" + Integer.toUnsignedString(name.toLowerCase(Locale.ROOT).hashCode(), 36);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\p{P}\\p{S}\\s]", "")
                .toLowerCase(Locale.ROOT);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value));
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private Long nullableLong(Object value) {
        return value == null ? null : number(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String textOrNull(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : text(second);
    }

    private IllegalArgumentException duplicateProject(DataIntegrityViolationException cause) {
        return new IllegalArgumentException("同一作用域内的项目名称或项目编码已存在", cause);
    }
}
