package com.example.aics.agent;

import com.example.aics.config.AdminDataScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预约登记管理接口（受管理口令保护）
 * GET /appointments            列表（?status=new 过滤）
 * PUT /appointments/{id}       更新状态（body: {"status":"contacted"}）
 */
@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final JdbcTemplate jdbc;

    public AppointmentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        int p = Math.max(1, page), size = Math.min(100, Math.max(1, pageSize));
        boolean filtered = status != null && !status.isBlank();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (filtered) { where.append(" AND a.status=?"); args.add(status); }
        AdminDataScope.appendAnd(where, args, "a");
        Integer total = jdbc.queryForObject("SELECT count(*) FROM appointment a" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add((p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.* FROM appointment a" + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "rows", rows);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateStatus(@PathVariable long id,
                                            @RequestBody Map<String, String> body) {
        String status = requireStatus(body.get("status"));
        AdminDataScope.Condition scope = AdminDataScope.condition("appointment");
        List<Object> args = new ArrayList<>(List.of(status, id));
        args.addAll(scope.args());
        int n = jdbc.update("UPDATE appointment SET status = ? WHERE id = ?" +
                (scope.restricted() ? " AND " + scope.sql() : ""), args.toArray());
        return Map.of("updated", n);
    }

    private String requireStatus(String status) {
        if (!List.of("new", "contacted", "closed", "cancelled").contains(status)) {
            throw new IllegalArgumentException("预约状态不正确");
        }
        return status;
    }
}
