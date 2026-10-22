package com.example.aics.agent;

import com.example.aics.audit.AuditService;
import com.example.aics.config.CustomerIdentityService;
import com.example.aics.config.AdminDataScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 唤醒任务接口
 * GET  /wakeup              任务列表（?status=new 过滤，受管理鉴权保护）
 * GET  /wakeup/mine         客户查自己的未读唤醒任务（无口令，只查自己）
 * PUT  /wakeup/{id}         更新状态（new/delivered/contacted，受管理鉴权保护）
 * POST /wakeup/run          手动触发一次扫描（测试用，受管理鉴权保护）
 * GET  /wakeup/candidates   批量运营圈人预览（P2-11，受管理鉴权保护）
 * POST /wakeup/batch        批量生成唤醒任务（P2-11，含冷却期频控，受管理鉴权保护）
 */
@RestController
@RequestMapping("/wakeup")
public class WakeupController {

    private final JdbcTemplate jdbc;
    private final WakeupService wakeupService;
    private final CustomerIdentityService customerIdentities;
    private final AuditService auditService;

    public WakeupController(JdbcTemplate jdbc, WakeupService wakeupService,
                            CustomerIdentityService customerIdentities,
                            AuditService auditService) {
        this.jdbc = jdbc;
        this.wakeupService = wakeupService;
        this.customerIdentities = customerIdentities;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        int p = Math.max(1, page), size = Math.min(100, Math.max(1, pageSize));
        boolean filtered = status != null && !status.isBlank();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (filtered) { where.append(" AND w.status=?"); args.add(status); }
        AdminDataScope.appendAnd(where, args, "w");
        Integer total = jdbc.queryForObject("SELECT count(*) FROM wake_task w" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add((p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT w.* FROM wake_task w" + where + " ORDER BY w.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "rows", rows);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateStatus(@PathVariable long id,
                                            @RequestBody Map<String, String> body) {
        String status = requireStatus(body.get("status"));
        AdminDataScope.Condition scope = AdminDataScope.condition("wake_task");
        List<Object> args = new ArrayList<>(List.of(status, id));
        args.addAll(scope.args());
        int n = jdbc.update("UPDATE wake_task SET status = ? WHERE id = ?" +
                (scope.restricted() ? " AND " + scope.sql() : ""), args.toArray());
        return Map.of("updated", n);
    }

    private String requireStatus(String status) {
        if (!List.of("new", "delivered", "contacted", "closed").contains(status)) {
            throw new IllegalArgumentException("唤醒任务状态不正确");
        }
        return status;
    }

    /** 客户查自己的未读唤醒任务；身份只取服务端 HttpOnly Cookie。 */
    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestParam(required = false) String userId,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wake_task WHERE user_id = ? AND status = 'new' " +
                "ORDER BY id DESC LIMIT 1", effectiveUserId);
        if (rows.isEmpty()) return Map.of("found", false);
        return Map.of("found", true, "task", rows.get(0));
    }

    /** 客户看到唤醒消息后点"知道了"；Cookie 身份与任务归属必须一致。 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable long id,
                                        @RequestParam(required = false) String userId,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        int n = jdbc.update(
                "UPDATE wake_task SET status = 'delivered' " +
                "WHERE id = ? AND user_id = ? AND status = 'new'", id, effectiveUserId);
        return Map.of("ok", n > 0);
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        return Map.of("created", wakeupService.scan());
    }

    /** 批量运营：按项目圈人预览（最多 200 人，已排除待办与冷却期内用户）。 */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestParam String project,
                                          @RequestParam(required = false) Integer inactiveDays,
                                          @RequestParam(defaultValue = "100") int limit) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("请填写项目名");
        }
        return Map.of("rows", wakeupService.candidates(project, inactiveDays, limit));
    }

    /** 批量运营：为选中客户批量生成唤醒任务（统一话术 + 冷却期频控，单次上限 200 人）。 */
    @PostMapping("/batch")
    public Map<String, Object> batchCreate(@RequestBody Map<String, Object> body) {
        List<String> userIds = body.get("userIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        String script = body.get("script") instanceof String text ? text : null;
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一位客户");
        }
        if (userIds.size() > 200) {
            throw new IllegalArgumentException("单次批量最多 200 位客户，请分批操作");
        }
        Map<String, Object> result = wakeupService.createBatch(userIds, script);
        auditService.log("WAKEUP_BATCH_CREATE",
                "批量生成唤醒任务：新建 " + result.get("created") + "，跳过 " + result.get("skipped"),
                "admin");
        return result;
    }
}
