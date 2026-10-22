package com.example.aics.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * W26-M6 操作日志：记录管理端/坐席的关键写操作（删除/改密/新增等），审计可查。
 *
 * 用法：在关键写操作后调用 log(action, object, operator)，如
 *   audit.log("DELETE_IMAGE", "图片名", "admin");
 * 记录失败不影响主流程（try-catch 吞掉，日志只记 WARN）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写一条操作日志（失败不抛异常，审计是锦上添花） */
    public void log(String action, String object, String operator) {
        try {
            String effectiveOperator = operator;
            if (effectiveOperator == null || effectiveOperator.isBlank() || "admin".equals(effectiveOperator)) {
                var attrs = RequestContextHolder.getRequestAttributes();
                if (attrs instanceof ServletRequestAttributes servlet) {
                    Object actor = servlet.getRequest().getAttribute("aics.admin.actor");
                    if (actor != null && !String.valueOf(actor).isBlank()) effectiveOperator = String.valueOf(actor);
                }
            }
            jdbc.update("INSERT INTO audit_log(action, object, operator) VALUES (?, ?, ?)",
                    action,
                    object == null ? "" : String.valueOf(object).substring(0, Math.min(String.valueOf(object).length(), 255)),
                    effectiveOperator == null || effectiveOperator.isBlank() ? "system" : effectiveOperator);
        } catch (Exception e) {
            log.warn("写入操作日志失败: {}", e.getMessage());
        }
    }

    /** 最近 N 条操作日志（管理端只读列表，倒序；保留给旧调用方） */
    public List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList(
                "SELECT id, action, object, operator, create_time FROM audit_log " +
                "ORDER BY id DESC LIMIT ?",
                limit);
    }

    /**
     * 分页 + 筛选的操作日志查询：操作/对象关键字、操作人、时间范围；
     * 服务端分页，突破旧「最近 200 条」上限。
     */
    public Map<String, Object> query(String keyword, String operator,
                                     LocalDate from, LocalDate to, int page, int pageSize) {
        int p = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        // 关键字同时匹配操作类型码与操作对象（管理员不一定记得 action 代码）
        if (keyword != null && !keyword.isBlank()) {
            where.append("AND (action ILIKE ? OR object ILIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (operator != null && !operator.isBlank()) {
            where.append("AND operator ILIKE ? ");
            args.add("%" + operator.trim() + "%");
        }
        if (from != null) {
            where.append("AND create_time >= ? ");
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            where.append("AND create_time < ? ");
            args.add(to.plusDays(1).atStartOfDay());
        }
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, action, object, operator, create_time FROM audit_log" + where +
                        "ORDER BY id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "page", p, "pageSize", size, "rows", rows);
    }
}
