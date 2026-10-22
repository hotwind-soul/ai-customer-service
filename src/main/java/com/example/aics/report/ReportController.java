package com.example.aics.report;

import com.example.aics.config.AdminDataScope;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话分析报表接口（受管理鉴权保护）
 * 时间范围：可选 from/to（yyyy-MM-dd，含两端）；缺省回退 days 天回溯窗口；跨度上限 92 天防滥用。
 * GET /report/overview?days=7 或 ?from=&to=         核心指标总览 + 应答方式构成
 * GET /report/daily?days=14 或 ?from=&to=           每日趋势（会话量/消息量/转人工）
 * GET /report/hot-questions?days=7 或 ?from=&to=    热点问题 Top10
 * GET /report/export/overview|daily|hot-questions   CSV 导出（支持同样参数）
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    /** 导出与查询允许的最大时间跨度（天）。 */
    private static final int MAX_SPAN_DAYS = 92;

    /** 应答方式的中文展示名（与前端 typeName 保持同一口径）。 */
    private static final Map<String, String> MATCH_TYPE_NAMES = Map.ofEntries(
            Map.entry("SAFETY", "情绪安全"), Map.entry("INTRO", "AI首次项目介绍"),
            Map.entry("FACT", "结构化事实"), Map.entry("FAQ", "FAQ直回"),
            Map.entry("AGENT", "业务工具"), Map.entry("CLARIFY", "追问澄清"),
            Map.entry("AI", "AI生成"), Map.entry("REFUSAL", "兜底回复"), Map.entry("ERROR", "服务异常"),
            Map.entry("UNKNOWN", "未记录"));

    private record Range(LocalDate from, LocalDate to) {}

    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "7") int days,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return overviewData(range(from, to, days));
    }

    @GetMapping("/daily")
    public List<Map<String, Object>> daily(@RequestParam(defaultValue = "14") int days,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        return dailyData(range(from, to, days));
    }

    @GetMapping("/hot-questions")
    public List<Map<String, Object>> hotQuestions(@RequestParam(defaultValue = "7") int days,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        return hotQuestionsData(range(from, to, days), 10);
    }

    // ==================== CSV 导出 ====================

    @GetMapping("/export/overview")
    public ResponseEntity<byte[]> exportOverview(@RequestParam(defaultValue = "7") int days,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        Range r = range(from, to, days);
        Map<String, Object> data = overviewData(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> composition = (List<Map<String, Object>>) data.get("composition");
        int total = composition.stream().mapToInt(row -> ((Number) row.get("count")).intValue()).sum();
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : composition) {
            String type = String.valueOf(row.get("type"));
            long count = ((Number) row.get("count")).longValue();
            double ratio = total == 0 ? 0 : Math.round(count * 1000.0 / total) / 10.0;
            rows.add(List.of(matchTypeName(type), count, ratio));
        }
        return CsvExport.response("report-overview-" + fileTag(r),
                List.of("应答方式", "条数", "占比(%)"), rows);
    }

    @GetMapping("/export/daily")
    public ResponseEntity<byte[]> exportDaily(@RequestParam(defaultValue = "14") int days,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        Range r = range(from, to, days);
        List<List<Object>> rows = dailyData(r).stream()
                .map(row -> List.of(String.valueOf(row.get("day")), row.get("conversations"),
                        row.get("user_messages"), exportValue(row.get("handoffs"))))
                .toList();
        return CsvExport.response("report-daily-" + fileTag(r),
                List.of("日期", "有效咨询会话数", "用户消息数", "转人工会话数（未采集显示暂无数据）"), rows);
    }

    @GetMapping("/export/hot-questions")
    public ResponseEntity<byte[]> exportHotQuestions(@RequestParam(defaultValue = "7") int days,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        Range r = range(from, to, days);
        List<List<Object>> rows = hotQuestionsData(r, 100).stream()
                .map(row -> List.of(row.get("content"), row.get("count")))
                .toList();
        return CsvExport.response("report-hot-questions-" + fileTag(r),
                List.of("客户问题", "提问次数"), rows);
    }

    /**
     * 一键导出 Excel（P1-1 优化）：单文件含 4 个工作表（核心指标/应答构成/每日趋势/热点问题），
     * 时间范围参数与 CSV 导出一致。
     */
    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(@RequestParam(defaultValue = "7") int days,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) throws IOException {
        Range r = range(from, to, days);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // Sheet 1：应答构成
            Map<String, Object> overview = overviewData(r);
            writeSheet(wb, "核心指标", List.of("指标", "数值", "统计口径"), metricRows(overview));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> composition =
                    (List<Map<String, Object>>) overview.get("composition");
            int total = composition.stream()
                    .mapToInt(row -> ((Number) row.get("count")).intValue()).sum();
            List<List<Object>> sheet1Rows = new ArrayList<>();
            for (Map<String, Object> row : composition) {
                String type = String.valueOf(row.get("type"));
                long count = ((Number) row.get("count")).longValue();
                double ratio = total == 0 ? 0 : Math.round(count * 1000.0 / total) / 10.0;
                sheet1Rows.add(List.of(matchTypeName(type), count, ratio));
            }
            writeSheet(wb, "应答构成", List.of("应答方式", "条数", "占比(%)"), sheet1Rows);

            // Sheet 2：每日趋势
            List<List<Object>> sheet2Rows = dailyData(r).stream()
                    .map(row -> List.of(String.valueOf(row.get("day")), row.get("conversations"),
                            row.get("user_messages"), exportValue(row.get("handoffs"))))
                    .toList();
            writeSheet(wb, "每日趋势", List.of("日期", "有效咨询会话数", "用户消息数", "转人工会话数（未采集显示暂无数据）"), sheet2Rows);

            // Sheet 3：热点问题
            List<List<Object>> sheet3Rows = hotQuestionsData(r, 100).stream()
                    .map(row -> List.of(row.get("content"), row.get("count")))
                    .toList();
            writeSheet(wb, "热点问题", List.of("客户问题", "提问次数"), sheet3Rows);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"report-" + fileTag(r) + ".xlsx\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(out.size())
                    .body(out.toByteArray());
        }
    }

    private List<List<Object>> metricRows(Map<String, Object> d) {
        return List.of(
                List.of("统计期间", d.get("from") + " ~ " + d.get("to"), "含起止日期；实时排队不在期间报表内"),
                List.of("有效咨询会话数", d.get("conversations"), "期间实际收到客户消息的会话去重数，包含旧会话"),
                List.of("用户消息数", d.get("userMessages"), "期间客户消息总数"),
                List.of("有效留资率(%)", exportValue(d.get("leadRate")), "期间有效留资客户 / 期间可识别咨询客户；按企业、客服号和渠道隔离去重"),
                List.of("有效留资客户数", d.get("leadCustomers"), "同期间登记，手机号格式正确、意向项目非空且未取消；不代表预约成功"),
                List.of("咨询客户数", d.get("consultingCustomers"), "排除无法识别身份的匿名记录"),
                List.of("转人工率(%)", exportValue(d.get("handoffRate")), "记录期内进入人工流程的咨询会话 / 记录期内有效咨询会话；含排队"),
                List.of("转人工会话数", d.get("handoffs"), "实际进入人工流程的事件，期间按会话去重"),
                List.of("转人工统计会话数", d.get("handoffObservedConversations"), "从 " + d.get("handoffTrackedFrom") + " 开始；更早历史未采集"),
                List.of("知识不足率(%)", exportValue(d.get("knowledgeGapRate")), "知识不足回复 / 已分类业务范围内回复；不含范围外、安全响应及业务信息待核实"),
                List.of("知识不足回复数", d.get("knowledgeGapReplies"), "明确记录缺少相关可靠知识的回复"),
                List.of("知识统计样本数", d.get("knowledgeEligibleReplies"), "已分类的业务范围内回复"),
                List.of("未分类回复数", d.get("unclassifiedReplies"), "历史原因未记录，不当作知识不足或成功回答"),
                List.of("超出业务范围回复数", d.get("outOfScopeReplies"), "单独观察，不计为知识不足"),
                List.of("业务信息待核实回复数", d.get("businessUnavailableReplies"), "权威业务信息不可用，单独观察"),
                List.of("AI首响中位数(ms)", exportValue(d.get("firstResponseMedianMs")), "服务端接收至首次成功发送有效回复；不含欢迎语、排队提示和发送失败"),
                List.of("AI首响P95(ms)", exportValue(d.get("firstResponseP95Ms")), "95%的成功采样回复在该时间内开始发送"),
                List.of("首响样本数", d.get("responseSamples"), "按请求去重，只统计已采集的成功发送记录")
        );
    }

    private Object exportValue(Object value) {
        return value == null ? "暂无数据" : value;
    }

    /** 极简 POI 写表助手：创建工作表 + 表头 + 逐行写值。 */
    private void writeSheet(XSSFWorkbook wb, String name, List<String> header, List<List<Object>> rows) {
        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(name);
        Row head = sheet.createRow(0);
        for (int i = 0; i < header.size(); i++) {
            head.createCell(i).setCellValue(header.get(i));
        }
        int rowIndex = 1;
        for (List<Object> cells : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < cells.size(); i++) {
                Object v = cells.get(i);
                if (v instanceof Number number) {
                    row.createCell(i).setCellValue(number.doubleValue());
                } else {
                    row.createCell(i).setCellValue(v == null ? "" : String.valueOf(v));
                }
            }
        }
    }

    // ==================== 数据组装 ====================

    private Map<String, Object> overviewData(Range r) {
        return new ReportMetrics(jdbc).overview(r.from(), r.to());
    }

    private List<Map<String, Object>> dailyData(Range r) {
        return new ReportMetrics(jdbc).daily(r.from(), r.to());
    }

    @GetMapping("/realtime")
    public Map<String, Object> realtime() {
        return new ReportMetrics(jdbc).realtime();
    }

    private List<Map<String, Object>> hotQuestionsData(Range r, int limit) {
        AdminDataScope.Condition scope = AdminDataScope.condition("c");
        List<Object> args = new ArrayList<>(timeArgs(r));
        args.addAll(scope.args());
        return jdbc.queryForList(
                "SELECT m.content, count(*) AS count FROM message m " +
                        "JOIN conversation c ON c.conversation_id=m.conversation_id " +
                        "WHERE m.role='user' AND m.create_time >= ? AND m.create_time < ? " +
                        (scope.restricted() ? "AND " + scope.sql() + " " : "") +
                        "GROUP BY m.content HAVING count(*) >= 1 ORDER BY count DESC, m.content LIMIT " + limit,
                args.toArray());
    }

    // ==================== 工具 ====================

    /** [起始时刻, 结束次日零点) 左闭右开时间窗参数。 */
    private List<Object> timeArgs(Range r) {
        return List.of(r.from().atStartOfDay(), r.to().plusDays(1).atStartOfDay());
    }

    private Range range(String from, String to, int defaultDays) {
        int safeDays = Math.min(defaultDays > 0 ? defaultDays : 7, MAX_SPAN_DAYS);
        LocalDate f = parseDate(from, LocalDate.now().minusDays(safeDays - 1L));
        LocalDate t = parseDate(to, LocalDate.now());
        if (f.isAfter(t)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        if (t.toEpochDay() - f.toEpochDay() >= MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("时间范围不能超过 " + MAX_SPAN_DAYS + " 天");
        }
        return new Range(f, t);
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd：" + value);
        }
    }

    private String matchTypeName(String type) {
        return MATCH_TYPE_NAMES.getOrDefault(type, type);
    }

    private String fileTag(Range r) {
        return r.from() + "_" + r.to();
    }

}
