package com.example.aics.audit;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 操作日志只读接口（受管理鉴权保护，见 WebConfig）。
 *
 * GET /api/audit-logs?action=&operator=&from=yyyy-MM-dd&to=yyyy-MM-dd&page=1&pageSize=20
 * 关键字同时匹配操作类型码（action）与操作对象（object）；时间含两端；服务端分页。
 */
@RestController
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/api/audit-logs")
    public Map<String, Object> list(@RequestParam(required = false) String action,
                                    @RequestParam(required = false) String operator,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return auditService.query(action, operator, from, to, page, pageSize);
    }
}

