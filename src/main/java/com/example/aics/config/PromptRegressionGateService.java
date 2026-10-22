package com.example.aics.config;

import com.example.aics.ai.ModelGateway;
import com.example.aics.audit.AuditService;
import com.example.aics.chat.ChatEngine;
import com.example.aics.chat.HarnessAssertions;
import com.example.aics.chat.HarnessCaseRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 在后台用完整 Prompt 草稿运行全部启用回归用例，并生成与草稿、用例集绑定的发布凭证。
 */
@Service
public class PromptRegressionGateService {
    private static final Logger log = LoggerFactory.getLogger(PromptRegressionGateService.class);

    private final PromptReleaseService releases;
    private final PromptRegressionCaseCatalog cases;
    private final PromptRegressionGateRepository repository;
    private final ChatEngine chatEngine;
    private final ModelGateway modelGateway;
    private final AuditService audit;
    private final HarnessEnvironmentService environment;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3), new ThreadPoolExecutor.AbortPolicy());

    public PromptRegressionGateService(PromptReleaseService releases,
                                       PromptRegressionCaseCatalog cases,
                                       PromptRegressionGateRepository repository,
                                       ChatEngine chatEngine,
                                       ModelGateway modelGateway,
                                       AuditService audit, HarnessEnvironmentService environment) {
        this.releases = releases;
        this.cases = cases;
        this.repository = repository;
        this.chatEngine = chatEngine;
        this.modelGateway = modelGateway;
        this.audit = audit;
        this.environment = environment;
    }

    @PostConstruct
    void recoverInterruptedTasks() {
        repository.failInterrupted();
    }

    public synchronized Map<String, Object> submit(String actor, boolean superAdmin) {
        PromptReleaseService.GateCandidate candidate = releases.gateCandidate(actor, superAdmin);
        PromptRegressionCaseCatalog.Snapshot caseSet = cases.snapshot();
        if (caseSet.cases().isEmpty()) {
            throw new IllegalArgumentException("没有已启用的回归用例，请先在回归中心建立并启用用例");
        }
        if (repository.hasActive(actor)) {
            throw new IllegalArgumentException("当前已有 Prompt 发布检查正在运行，请等待完成");
        }
        String environmentHash = environment.fingerprint();
        long taskId = repository.create(actor, candidate.baseReleaseId(), candidate.draftHash(),
                caseSet.hash(), environmentHash, caseSet.cases().size());
        AdminDataScope.AccessScope scope = AdminDataScope.current();
        try {
            executor.execute(() -> run(taskId, actor, candidate, caseSet, scope, environmentHash));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            repository.fail(taskId, "Prompt 发布检查队列已满，请稍后重试");
            throw new IllegalArgumentException("Prompt 发布检查队列已满，请稍后重试");
        }
        audit.log("SUBMIT_PROMPT_REGRESSION_GATE", "发布检查 #" + taskId
                + "：" + caseSet.cases().size() + " 条", actor);
        return repository.status(taskId, actor);
    }

    void run(long taskId, String actor, PromptReleaseService.GateCandidate candidate,
             PromptRegressionCaseCatalog.Snapshot caseSet, AdminDataScope.AccessScope scope, String environmentHash) {
        List<Map<String, Object>> failures = new ArrayList<>();
        int passed = 0;
        try (AdminDataScope.Lease ignored = AdminDataScope.use(scope)) {
            repository.markRunning(taskId);
            requireEnvironment(environmentHash);
            for (int index = 0; index < caseSet.cases().size(); index++) {
                PromptRegressionCaseCatalog.CaseDefinition item = caseSet.cases().get(index);
                repository.progress(taskId, index, passed, failures, item.id());
                Map<String, Object> failure = runCase(taskId, item, candidate.values());
                if (failure == null) passed++;
                else failures.add(failure);
                repository.progress(taskId, index + 1, passed, failures, null);
            }
            requireEnvironment(environmentHash);
            boolean allPassed = !caseSet.cases().isEmpty() && failures.isEmpty() && passed == caseSet.cases().size();
            repository.finish(taskId, allPassed, passed, failures);
            audit.log("COMPLETE_PROMPT_REGRESSION_GATE", "发布检查 #" + taskId
                    + "：通过 " + passed + "，失败 " + failures.size(), actor);
        } catch (Exception e) {
            String message = deepestMessage(e);
            repository.fail(taskId, message);
            log.warn("Prompt 发布检查失败: taskId={}, error={}", taskId, message, e);
        }
    }

    public Map<String, Object> status(long taskId, String actor) {
        Map<String, Object> result = new LinkedHashMap<>(repository.status(taskId, actor));
        if (Boolean.TRUE.equals(result.get("proofValid"))) {
            result.put("proofValid", environment.fingerprint().equals(result.get("environmentHash")));
        }
        return result;
    }

    public Map<String, Object> latest(String actor, boolean superAdmin) {
        try {
            PromptReleaseService.GateCandidate candidate = releases.gateCandidate(actor, superAdmin);
            PromptRegressionCaseCatalog.Snapshot caseSet = cases.snapshot();
            return repository.latest(actor, candidate.baseReleaseId(), candidate.draftHash(), caseSet.hash(),
                    environment.fingerprint());
        } catch (IllegalArgumentException | ResponseStatusException e) {
            // gateCandidate 在「草稿基于旧版本(409)」或「草稿含超管专属键(403)」时抛 ResponseStatusException；
            // 本接口是前端轮询用的只读接口，这些情况应优雅返回 found=false，而不是把 409/403 抛出去打断轮询。
            return Map.of("found", false);
        }
    }

    private void requireEnvironment(String expected) {
        if (expected == null || expected.isBlank() || !expected.equals(environment.fingerprint())) {
            throw new IllegalStateException("模型配置或知识数据在检查期间发生变化，请重新运行发布检查");
        }
    }

    private Map<String, Object> runCase(long taskId,
                                        PromptRegressionCaseCatalog.CaseDefinition item,
                                        Map<String, String> values) {
        try {
            ChatEngine.DebugOptions options = new ChatEngine.DebugOptions(
                    safe(item.history()), "", null, null,
                    values.get("prompt.tone"), values.get("refusal.answer"),
                    values.get("agent.systemPrompt"), values.get("prompt.ragRules"),
                    values.get("prompt.moduleCharBudgets"), values.get("agent.classifierPrompt"),
                    null, null, values.get("projectIntro.decisionPrompt"));
            ChatEngine.Prepared prepared = chatEngine.prepareDebug(
                    item.message(), "prompt-gate-" + taskId + "-" + item.id(),
                    "prompt-gate", null, null, options);
            HarnessCaseRunner.Outcome outcome = HarnessCaseRunner.run(prepared,
                    new HarnessAssertions.Spec(item.expectedReplyType(), item.expectedAnswerPoints(),
                            item.forbiddenAnswerPoints(), item.expectedAuthorityDomain(),
                            item.expectedAuthoritySource(), item.expectedModelInvoked()),
                    false, chatEngine, modelGateway);
            HarnessAssertions.Result assertion = outcome.assertion();
            if (assertion.passed()) return null;
            return failure(item, outcome.error() == null ? prepared.replyType() : "ERROR", assertion.failures());
        } catch (Exception e) {
            return failure(item, "ERROR", List.of("执行失败：" + deepestMessage(e)));
        }
    }

    private Map<String, Object> failure(PromptRegressionCaseCatalog.CaseDefinition item,
                                        String actualReplyType, List<String> reasons) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", item.id());
        result.put("name", item.name());
        result.put("actualReplyType", actualReplyType == null ? "" : actualReplyType);
        result.put("reasons", reasons == null ? List.of("未知失败") : List.copyOf(reasons));
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String deepestMessage(Throwable error) {
        String message = null;
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) message = current.getMessage();
            current = current.getCause();
        }
        if (message == null) message = error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500) + "…";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
