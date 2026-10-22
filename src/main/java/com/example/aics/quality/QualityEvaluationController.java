package com.example.aics.quality;

import com.example.aics.report.CsvExport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quality")
public class QualityEvaluationController {
    private final ConversationEvaluationService evaluations;
    private final QualityEvaluationBatchService batches;

    public QualityEvaluationController(ConversationEvaluationService evaluations,
                                       QualityEvaluationBatchService batches) {
        this.evaluations = evaluations;
        this.batches = batches;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return evaluations.overview();
    }

    @GetMapping("/judge-models")
    public Map<String, Object> judgeModels() {
        return evaluations.judgeModels();
    }

    /** 返回当前生效的质量评分规则（默认 + 配置覆盖），含 enabled/pattern/allDeductions 供编辑。 */
    @GetMapping("/rules")
    public List<QualityRuleEngine.RuleView> rules() {
        return evaluations.effectiveRules();
    }

    /** 保存质量评分规则配置（覆盖项），仅 quality.manage 权限可调用，写入后立即生效。 */
    @PutMapping("/rules")
    public Map<String, Object> updateRules(@RequestBody(required = false) Map<String, QualityRuleEngine.RuleConfig> body,
                                           HttpServletRequest request) {
        return evaluations.updateRules(body == null ? Map.of() : body, actor(request));
    }

    @GetMapping("/evaluations")
    public Map<String, Object> query(@RequestParam(required = false) Integer days,
                                     @RequestParam(required = false) Integer minScore,
                                     @RequestParam(required = false) Integer maxScore,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String channel,
                                     @RequestParam(required = false) String issueType,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer pageSize) {
        return evaluations.query(days, minScore, maxScore, status, channel, issueType, page, pageSize);
    }

    @GetMapping("/evaluations/conversation/{conversationId}")
    public Map<String, Object> detail(@PathVariable String conversationId) {
        return evaluations.detailByConversation(conversationId);
    }

    /**
     * 导出评分记录 CSV：复用列表的全部筛选条件，按 100 条/页滚动拉取，上限 2000 条。
     */
    @GetMapping("/evaluations/export")
    public ResponseEntity<byte[]> exportEvaluations(@RequestParam(required = false) Integer days,
                                                    @RequestParam(required = false) Integer minScore,
                                                    @RequestParam(required = false) Integer maxScore,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String channel,
                                                    @RequestParam(required = false) String issueType) {
        int pageSize = 200;
        int maxRows = 2000;
        // 不能用「边翻页边读会变化的 total」：导出期间自动评测/规则扫描会写入新记录，
        // 排序窗口整体位移，OFFSET 分页会重复或漏行。改为按稳定排序翻页 + 按会话去重，
        // 并以"短页"作为终止条件（不再依赖 total），另加页数上限兜底防止空转。
        Map<String, List<Object>> unique = new LinkedHashMap<>();
        int maxPages = maxRows / pageSize + 2;
        for (int page = 1; page <= maxPages && unique.size() < maxRows; page++) {
            Map<String, Object> result = evaluations.query(days, minScore, maxScore, status,
                    channel, issueType, page, pageSize);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> batch = (List<Map<String, Object>>) result.getOrDefault("rows", List.of());
            if (batch.isEmpty()) break;
            for (Map<String, Object> row : batch) {
                String key = row.get("conversationId") + "|" + row.get("evaluatedAt");
                unique.putIfAbsent(key, List.of(
                        row.get("conversationId"), row.get("userId"), row.get("channel"),
                        row.get("totalScore"), row.get("riskScore"), row.get("grade"), row.get("status"),
                        row.get("firstQuestion"), row.get("messageCount"), issuesText(row.get("issues")),
                        row.get("evaluatedAt")));
            }
            if (batch.size() < pageSize) break;   // 已到最后一页
        }
        return CsvExport.response("quality-evaluations-" + LocalDate.now() + ".csv",
                List.of("会话ID", "用户ID", "渠道", "总分", "风险分", "等级", "阶段",
                        "首个问题", "消息数", "问题分类", "评测时间"),
                List.copyOf(unique.values()));
    }

    private String issuesText(Object issues) {
        if (!(issues instanceof List<?> list) || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (sb.length() > 0) sb.append("; ");
            if (item instanceof Map<?, ?> map) {
                Object category = map.get("category");
                Object description = map.get("description");
                sb.append(category == null ? "" : category);
                if (description != null && !String.valueOf(description).isBlank()) {
                    sb.append('：').append(description);
                }
            } else {
                sb.append(item);
            }
        }
        return sb.toString();
    }

    @PostMapping("/evaluations/{conversationId}/score")
    public Map<String, Object> score(@PathVariable String conversationId,
                                     @RequestBody(required = false) ScoreRequest body,
                                     HttpServletRequest request) {
        return evaluations.score(conversationId, judgeProvider(body), actor(request));
    }

    @PostMapping("/evaluations/batch-score")
    public Map<String, Object> batchScore(@RequestBody(required = false) BatchScoreRequest body,
                                          HttpServletRequest request) {
        return batches.submit(body == null ? List.of() : body.conversationIds(),
                judgeProvider(body), actor(request));
    }

    @GetMapping("/evaluations/batch-score/latest")
    public Map<String, Object> latestBatch(HttpServletRequest request) {
        return batches.latest(actor(request));
    }

    @GetMapping("/evaluations/batch-score/{taskId}")
    public Map<String, Object> batchStatus(@PathVariable long taskId, HttpServletRequest request) {
        return batches.status(taskId, actor(request));
    }

    @PutMapping("/evaluations/{evaluationId}/review")
    public Map<String, Object> review(@PathVariable long evaluationId,
                                      @RequestBody ReviewRequest body,
                                      HttpServletRequest request) {
        return evaluations.review(evaluationId, body.result(), body.note(), body.scores(), actor(request));
    }

    @PostMapping("/evaluations/{evaluationId}/regression-case")
    public Map<String, Object> addRegressionCase(@PathVariable long evaluationId,
                                                  HttpServletRequest request) {
        return evaluations.addRegressionFromEvaluation(evaluationId, actor(request));
    }

    @GetMapping("/regression-cases")
    public List<Map<String, Object>> regressionCases(
            @RequestParam(defaultValue = "true") boolean includeDisabled) {
        return evaluations.regressionCases(includeDisabled);
    }

    @PostMapping("/regression-cases")
    public Map<String, Object> createRegressionCase(@RequestBody Map<String, Object> input,
                                                     HttpServletRequest request) {
        return evaluations.createRegression(input, actor(request));
    }

    @PutMapping("/regression-cases/{id}")
    public Map<String, Object> updateRegressionCase(@PathVariable long id,
                                                     @RequestBody Map<String, Object> input,
                                                     HttpServletRequest request) {
        return evaluations.updateRegression(id, input, actor(request));
    }

    @DeleteMapping("/regression-cases/{id}")
    public Map<String, Object> deleteRegressionCase(@PathVariable long id,
                                                     HttpServletRequest request) {
        evaluations.deleteRegression(id, actor(request));
        return Map.of("ok", true);
    }

    private String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("aics.admin.actor");
        return actor == null || String.valueOf(actor).isBlank() ? "admin" : String.valueOf(actor);
    }

    private String judgeProvider(ScoreRequest body) {
        if (body == null) return "rules";
        if (body.judgeProvider() != null && !body.judgeProvider().isBlank()) return body.judgeProvider();
        return Boolean.TRUE.equals(body.useAiJudge()) ? "fallback" : "rules";
    }

    private String judgeProvider(BatchScoreRequest body) {
        if (body == null) return "rules";
        if (body.judgeProvider() != null && !body.judgeProvider().isBlank()) return body.judgeProvider();
        return Boolean.TRUE.equals(body.useAiJudge()) ? "fallback" : "rules";
    }

    /** useAiJudge 仅保留用于兼容旧版管理页面；新版使用 judgeProvider。 */
    public record ScoreRequest(String judgeProvider, Boolean useAiJudge) {}
    public record BatchScoreRequest(List<String> conversationIds, String judgeProvider, Boolean useAiJudge) {}
    public record ReviewRequest(String result, String note, Map<String, Integer> scores) {}
}
