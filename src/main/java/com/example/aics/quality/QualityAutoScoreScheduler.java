package com.example.aics.quality;

import com.example.aics.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 自动评测调度器（每 2 分钟轮询一次）：
 * 1) 为新对话执行默认规则风险扫描（轻量、零成本，非质量分，受 newConvRuleEvalEnabled 控制）；
 * 2) 当 quality.autoScoreEnabled 开启、且处于配置的 [开始,结束) 时间窗口内、且当日尚未执行过时，
 *    触发一次自动定时评测（实际逻辑在 {@link ConversationEvaluationService#autoScore()}）。
 */
@Component
public class QualityAutoScoreScheduler {
    private static final Logger log = LoggerFactory.getLogger(QualityAutoScoreScheduler.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final ConversationEvaluationService evaluationService;
    private final ConfigService config;
    private LocalDate lastAutoScoreDate = null;

    public QualityAutoScoreScheduler(ConversationEvaluationService evaluationService, ConfigService config) {
        this.evaluationService = evaluationService;
        this.config = config;
    }

    @Scheduled(cron = "0 */2 * * * ?", zone = "Asia/Shanghai")
    public void tick() {
        try {
            evaluationService.ruleScoreNewConversations();
        } catch (Exception e) {
            log.warn("新对话默认规则评测异常：{}", e.getMessage());
        }
        try {
            maybeAutoScore();
        } catch (Exception e) {
            log.error("autoScore 定时任务异常：{}", e.getMessage(), e);
        }
    }

    private void maybeAutoScore() {
        if (!config.getBoolean("quality.autoScoreEnabled", false)) return;
        LocalDate today = LocalDate.now(SHANGHAI);
        if (today.equals(lastAutoScoreDate)) return; // 当天已执行过
        if (!withinWindow()) return;                  // 不在时间窗口内
        boolean executed = true; // 异常也视为当日已处理，避免异常时每 2 分钟反复触发
        try {
            Map<String, Object> result = evaluationService.autoScore();
            // autoScore 在「AI 裁判未启用」或「裁判模型未配好」时是正常返回 skipped 而非抛异常；
            // 若把它当成"当日已执行"，窗口内不会再重试，当日自动评测会直接丢失。
            if (Boolean.TRUE.equals(result.get("skipped"))) {
                executed = false;
                log.warn("自动评测本次跳过（reason={}），将在本次窗口内重试", result.get("reason"));
            }
        } catch (Exception e) {
            log.error("autoScore 执行异常：{}", e.getMessage(), e);
        } finally {
            if (executed) lastAutoScoreDate = today;
        }
    }

    private boolean withinWindow() {
        int sh = config.getInt("quality.autoScoreStartHour", 2);
        int sm = config.getInt("quality.autoScoreStartMinute", 0);
        int eh = config.getInt("quality.autoScoreEndHour", 6);
        int em = config.getInt("quality.autoScoreEndMinute", 0);
        LocalTime start = LocalTime.of(clamp(sh, 0, 23), clamp(sm, 0, 59));
        LocalTime end = LocalTime.of(clamp(eh, 0, 23), clamp(em, 0, 59));
        if (start.equals(end)) {
            return false;   // 零长度窗口按未配置处理；静默跳过，避免每 2 分钟刷一条 warn
        }
        LocalTime now = LocalTime.now(SHANGHAI);
        if (end.isAfter(start)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨午夜窗口（如 23:00–02:00）：原来被判定为"配置无效"，会导致自动评测每天都不执行。
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static int clamp(int v, int lo, int hi) { return Math.min(hi, Math.max(lo, v)); }
}
