package com.example.aics.config;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prompt 配置发布治理。
 *
 * <p>每位管理员拥有独立草稿；发布时校验基础版本并一次性写入 system_config，
 * 防止多人编辑时静默覆盖。每次发布和回滚都会生成新的不可变快照。</p>
 */
@Service
public class PromptReleaseService {
    private static final String INTERNAL_RELEASE_VERSION_KEY = "prompt.releaseVersion";

    private final PromptReleaseStore store;
    private final ConfigService config;
    private final PromptRegressionGateRepository gateRepository;
    private final PromptRegressionCaseCatalog regressionCases;
    private final HarnessEnvironmentService environment;

    public PromptReleaseService(PromptReleaseStore store, ConfigService config,
                                PromptRegressionGateRepository gateRepository,
                                PromptRegressionCaseCatalog regressionCases, HarnessEnvironmentService environment) {
        this.store = store;
        this.config = config;
        this.gateRepository = gateRepository;
        this.regressionCases = regressionCases;
        this.environment = environment;
    }

    public record GateCandidate(long baseReleaseId, String draftHash, Map<String, String> values) {
        public GateCandidate {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    @Transactional
    public Map<String, Object> workspace(String actor, boolean superAdmin) {
        String owner = normalizeActor(actor);
        PromptReleaseStore.Release current = currentReleaseLocked(owner);
        PromptReleaseStore.Draft draft = store.findDraft(owner);
        return workspaceMap(current, draft, superAdmin);
    }

    @Transactional
    public Map<String, Object> saveDraft(String actor, boolean superAdmin, Map<String, ?> values) {
        String owner = normalizeActor(actor);
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("没有需要保存的 Prompt 草稿");
        if (values.size() > AiConfigCatalog.versionedPromptKeys().size()) {
            throw new IllegalArgumentException("Prompt 草稿配置项过多");
        }

        PromptReleaseStore.Release current = currentReleaseLocked(owner);
        PromptReleaseStore.Draft existing = store.findDraft(owner);
        if (existing != null && existing.baseReleaseId() != current.id()) {
            throw staleDraft();
        }

        Map<String, String> snapshot = new LinkedHashMap<>(
                existing == null ? current.snapshot() : existing.snapshot());
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            AiConfigCatalog.Setting setting = requirePromptSetting(key);
            ensureCanChange(setting, superAdmin);
            snapshot.put(key, AiConfigCatalog.validateAndNormalize(
                    key, entry.getValue() == null ? "" : String.valueOf(entry.getValue())));
        }
        snapshot = validatedSnapshot(snapshot);
        List<String> changedKeys = changedKeys(current.snapshot(), snapshot);
        if (changedKeys.isEmpty()) {
            store.deleteDraft(owner);
            return workspaceMap(current, null, superAdmin);
        }
        store.saveDraft(owner, current.id(), snapshot);
        return workspaceMap(current, store.findDraft(owner), superAdmin);
    }

    @Transactional
    public Map<String, Object> discardDraft(String actor, boolean superAdmin) {
        String owner = normalizeActor(actor);
        PromptReleaseStore.Release current = currentReleaseLocked(owner);
        store.deleteDraft(owner);
        return workspaceMap(current, null, superAdmin);
    }

    @Transactional
    public Map<String, Object> publishDraft(String actor, boolean superAdmin) {
        String owner = normalizeActor(actor);
        PromptReleaseStore.Release current = currentReleaseLocked(owner);
        PromptReleaseStore.Draft draft = store.findDraft(owner);
        if (draft == null) throw new IllegalArgumentException("当前没有已保存的 Prompt 草稿");
        if (draft.baseReleaseId() != current.id()) throw staleDraft();

        Map<String, String> target = validatedSnapshot(draft.snapshot());
        List<String> changedKeys = changedKeys(current.snapshot(), target);
        if (changedKeys.isEmpty()) {
            store.deleteDraft(owner);
            throw new IllegalArgumentException("Prompt 草稿与当前线上版本相同，无需发布");
        }
        ensureCanChange(changedKeys, superAdmin);
        PromptRegressionCaseCatalog.Snapshot caseSet = regressionCases.snapshot();
        if (caseSet.cases().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "没有已启用的回归用例，不能发布 Prompt；请先在回归中心建立并启用用例");
        }
        if (!gateRepository.hasPassingProof(owner, current.id(), snapshotHash(target), caseSet.hash(),
                environment.fingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前 Prompt 草稿尚未通过发布检查，或草稿/回归用例/运行环境已发生变化；请重新运行发布检查");
        }

        PromptReleaseStore.Release release = store.insertRelease(
                "PUBLISH", null, target, changedKeys, owner);
        applyRelease(release, current.snapshot());
        store.deleteDraft(owner);
        return Map.of(
                "result", "Prompt 新版本已发布并立即生效",
                "release", releaseSummary(release, superAdmin));
    }

    @Transactional
    public Map<String, Object> rollback(long releaseId, String actor, boolean superAdmin) {
        String owner = normalizeActor(actor);
        PromptReleaseStore.Release current = currentReleaseLocked(owner);
        PromptReleaseStore.Release source = store.findRelease(releaseId);
        if (source == null) throw new IllegalArgumentException("找不到指定的 Prompt 历史版本");

        Map<String, String> target = validatedSnapshot(source.snapshot());
        List<String> changedKeys = changedKeys(current.snapshot(), target);
        if (changedKeys.isEmpty()) throw new IllegalArgumentException("所选版本与当前线上版本相同");
        ensureCanChange(changedKeys, superAdmin);

        PromptReleaseStore.Release release = store.insertRelease(
                "ROLLBACK", source.id(), target, changedKeys, owner);
        applyRelease(release, current.snapshot());
        store.deleteDraft(owner);
        return Map.of(
                "result", "已回滚并生成新的 Prompt 版本",
                "release", releaseSummary(release, superAdmin));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(int requestedLimit, boolean superAdmin) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return store.listReleases(limit).stream()
                .map(release -> releaseSummary(release, superAdmin))
                .toList();
    }

    @Transactional
    public Map<String, Object> releaseDetail(long releaseId, boolean superAdmin) {
        PromptReleaseStore.Release release = store.findRelease(releaseId);
        if (release == null) throw new IllegalArgumentException("找不到指定的 Prompt 历史版本");
        PromptReleaseStore.Release previous = store.findPreviousRelease(releaseId);
        Long currentId = store.lockCurrentReleaseId();
        PromptReleaseStore.Release current = currentId == null ? null : store.findRelease(currentId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("release", releaseSummary(release, superAdmin));
        result.put("diffFromPrevious", previous == null
                ? List.of()
                : differences(previous.snapshot(), release.snapshot(), superAdmin));
        result.put("diffFromCurrent", differences(
                current == null ? Map.of() : current.snapshot(), release.snapshot(), superAdmin));
        result.put("current", current != null && current.id() == release.id());
        return result;
    }

    private PromptReleaseStore.Release currentReleaseLocked(String actor) {
        Long currentId = store.lockCurrentReleaseId();
        if (currentId != null) {
            PromptReleaseStore.Release current = store.findRelease(currentId);
            if (current == null) throw new IllegalStateException("当前 Prompt 版本记录不存在");
            return current;
        }
        Map<String, String> baseline = effectiveSnapshot();
        PromptReleaseStore.Release release = store.insertRelease(
                "BASELINE", null, baseline, List.of(), actor);
        store.setCurrentReleaseId(release.id());
        config.set(INTERNAL_RELEASE_VERSION_KEY, versionLabel(release.id()));
        return release;
    }

    private void applyRelease(PromptReleaseStore.Release release, Map<String, String> previous) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : changedKeys(previous, release.snapshot())) {
            values.put(key, release.snapshot().get(key));
        }
        values.put(INTERNAL_RELEASE_VERSION_KEY, versionLabel(release.id()));
        config.setAll(values);
        store.setCurrentReleaseId(release.id());
    }

    private Map<String, String> effectiveSnapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            if (AiConfigCatalog.isVersionedPromptKey(setting.key())) {
                result.put(setting.key(), AiConfigCatalog.validateAndNormalize(setting.key(),
                        config.get(setting.key(), setting.defaultValue())));
            }
        }
        return result;
    }

    private Map<String, String> validatedSnapshot(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            if (!AiConfigCatalog.isVersionedPromptKey(setting.key())) continue;
            String value = values.getOrDefault(setting.key(), setting.defaultValue());
            result.put(setting.key(), AiConfigCatalog.validateAndNormalize(setting.key(), value));
        }
        return result;
    }

    private Map<String, Object> workspaceMap(PromptReleaseStore.Release current,
                                             PromptReleaseStore.Draft draft,
                                             boolean superAdmin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentRelease", releaseSummary(current, superAdmin));
        result.put("versionedKeys", visibleKeys(superAdmin));
        if (draft == null) {
            result.put("draft", null);
        } else {
            Map<String, Object> draftMap = new LinkedHashMap<>();
            draftMap.put("baseReleaseId", draft.baseReleaseId());
            draftMap.put("baseVersion", versionLabel(draft.baseReleaseId()));
            draftMap.put("stale", draft.baseReleaseId() != current.id());
            draftMap.put("values", visibleValues(draft.snapshot(), superAdmin));
            draftMap.put("draftHash", snapshotHash(validatedSnapshot(draft.snapshot())));
            draftMap.put("changedKeys", visibleChangedKeys(
                    changedKeys(current.snapshot(), draft.snapshot()), superAdmin));
            draftMap.put("updateTime", draft.updateTime());
            result.put("draft", draftMap);
        }
        return result;
    }

    /** 为发布门禁取得与当前版本绑定的完整草稿；不会暴露给浏览器。 */
    @Transactional
    GateCandidate gateCandidate(String actor, boolean superAdmin) {
        String owner = normalizeActor(actor);
        PromptReleaseStore.Release current = currentReleaseLocked(owner);
        PromptReleaseStore.Draft draft = store.findDraft(owner);
        if (draft == null) throw new IllegalArgumentException("当前没有已保存的 Prompt 草稿");
        if (draft.baseReleaseId() != current.id()) throw staleDraft();
        Map<String, String> target = validatedSnapshot(draft.snapshot());
        List<String> changedKeys = changedKeys(current.snapshot(), target);
        if (changedKeys.isEmpty()) throw new IllegalArgumentException("Prompt 草稿与当前线上版本相同，无需检查");
        ensureCanChange(changedKeys, superAdmin);
        return new GateCandidate(current.id(), snapshotHash(target), target);
    }

    private Map<String, Object> releaseSummary(PromptReleaseStore.Release release, boolean superAdmin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", release.id());
        result.put("version", versionLabel(release.id()));
        result.put("action", release.action());
        result.put("sourceReleaseId", release.sourceReleaseId());
        result.put("sourceVersion", release.sourceReleaseId() == null
                ? null : versionLabel(release.sourceReleaseId()));
        result.put("changedKeys", visibleChangedKeys(release.changedKeys(), superAdmin));
        result.put("createdBy", release.createdBy());
        result.put("createTime", release.createTime());
        return result;
    }

    private List<Map<String, Object>> differences(Map<String, String> before,
                                                  Map<String, String> after,
                                                  boolean superAdmin) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            if (!AiConfigCatalog.isVersionedPromptKey(setting.key())
                    || (setting.superOnly() && !superAdmin)) continue;
            String oldValue = snapshotValue(before, setting);
            String newValue = snapshotValue(after, setting);
            if (Objects.equals(oldValue, newValue)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", setting.key());
            row.put("label", setting.label());
            row.put("before", oldValue == null ? "" : oldValue);
            row.put("after", newValue == null ? "" : newValue);
            result.add(row);
        }
        return result;
    }

    private List<String> changedKeys(Map<String, String> before, Map<String, String> after) {
        List<String> result = new ArrayList<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            if (!AiConfigCatalog.isVersionedPromptKey(setting.key())) continue;
            if (!Objects.equals(snapshotValue(before, setting), snapshotValue(after, setting))) {
                result.add(setting.key());
            }
        }
        return List.copyOf(result);
    }

    private List<String> visibleKeys(boolean superAdmin) {
        return AiConfigCatalog.definitions().stream()
                .filter(setting -> AiConfigCatalog.isVersionedPromptKey(setting.key()))
                .filter(setting -> superAdmin || !setting.superOnly())
                .map(AiConfigCatalog.Setting::key)
                .toList();
    }

    private Map<String, String> visibleValues(Map<String, String> values, boolean superAdmin) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : visibleKeys(superAdmin)) {
            AiConfigCatalog.Setting setting = AiConfigCatalog.find(key);
            result.put(key, snapshotValue(values, setting));
        }
        return result;
    }

    private String snapshotValue(Map<String, String> values, AiConfigCatalog.Setting setting) {
        String raw = values == null ? setting.defaultValue()
                : values.getOrDefault(setting.key(), setting.defaultValue());
        return AiConfigCatalog.validateAndNormalize(setting.key(), raw);
    }

    private List<String> visibleChangedKeys(List<String> keys, boolean superAdmin) {
        return keys.stream().filter(key -> {
            AiConfigCatalog.Setting setting = AiConfigCatalog.find(key);
            return setting != null && (superAdmin || !setting.superOnly());
        }).toList();
    }

    private AiConfigCatalog.Setting requirePromptSetting(String key) {
        AiConfigCatalog.Setting setting = AiConfigCatalog.find(key);
        if (setting == null || !AiConfigCatalog.isVersionedPromptKey(key)) {
            throw new IllegalArgumentException("不支持的 Prompt 配置：" + key);
        }
        return setting;
    }

    private void ensureCanChange(List<String> keys, boolean superAdmin) {
        for (String key : keys) ensureCanChange(requirePromptSetting(key), superAdmin);
    }

    private void ensureCanChange(AiConfigCatalog.Setting setting, boolean superAdmin) {
        if (setting.superOnly() && !superAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "仅超级管理员可以修改高级 Prompt 配置：" + setting.label());
        }
    }

    private ResponseStatusException staleDraft() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "线上 Prompt 已发布新版本，当前草稿基于旧版本；请查看差异后放弃旧草稿或重新编辑");
    }

    private String normalizeActor(String actor) {
        if (actor == null || actor.isBlank()) return "admin";
        String normalized = actor.strip();
        return normalized.substring(0, Math.min(normalized.length(), 100));
    }

    static String snapshotHash(Map<String, String> snapshot) {
        StringBuilder value = new StringBuilder();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            if (!AiConfigCatalog.isVersionedPromptKey(setting.key())) continue;
            String item = snapshot == null ? "" : snapshot.getOrDefault(setting.key(), "");
            value.append(setting.key().length()).append(':').append(setting.key()).append('|')
                    .append(item.length()).append(':').append(item).append('|');
        }
        return PromptRegressionCaseCatalog.hash(value.toString());
    }

    static String versionLabel(long id) {
        return "prompt-v" + String.format(java.util.Locale.ROOT, "%04d", id);
    }
}
