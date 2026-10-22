package com.example.aics.config;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Prompt 草稿与不可变发布快照的持久化边界。 */
public interface PromptReleaseStore {

    record Release(
            long id,
            String action,
            Long sourceReleaseId,
            Map<String, String> snapshot,
            List<String> changedKeys,
            String createdBy,
            LocalDateTime createTime
    ) {}

    record Draft(
            String owner,
            long baseReleaseId,
            Map<String, String> snapshot,
            LocalDateTime updateTime
    ) {}

    /** 锁住单行发布状态，调用者必须处于事务内。 */
    Long lockCurrentReleaseId();

    void setCurrentReleaseId(long releaseId);

    Release insertRelease(String action, Long sourceReleaseId, Map<String, String> snapshot,
                          List<String> changedKeys, String actor);

    Release findRelease(long id);

    Release findPreviousRelease(long id);

    List<Release> listReleases(int limit);

    Draft findDraft(String owner);

    void saveDraft(String owner, long baseReleaseId, Map<String, String> snapshot);

    void deleteDraft(String owner);
}
