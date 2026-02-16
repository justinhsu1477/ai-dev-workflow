package com.team.aiworkflow.service.stats;

import com.team.aiworkflow.config.EngineerStatsConfig;
import com.team.aiworkflow.model.entity.BugRecord;
import com.team.aiworkflow.model.entity.BugRecord.ResolutionMethod;
import com.team.aiworkflow.model.entity.BugRecord.Severity;
import com.team.aiworkflow.model.entity.EngineerProfile;
import com.team.aiworkflow.model.entity.PushRecord;
import com.team.aiworkflow.model.entity.PushRecord.TestOutcome;
import com.team.aiworkflow.repository.BugRecordRepository;
import com.team.aiworkflow.repository.EngineerProfileRepository;
import com.team.aiworkflow.repository.PushRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 事件記錄器：在各流程的關鍵節點記錄資料到 DB。
 * 由 PushWebhookController、E2ETestOrchestrator、AutoFixOrchestrator 呼叫。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EngineerStatsRecorder {

    private final EngineerStatsConfig config;
    private final EngineerProfileRepository engineerRepo;
    private final PushRecordRepository pushRecordRepo;
    private final BugRecordRepository bugRecordRepo;

    /**
     * 記錄一次 Push 事件。
     * 在 PushWebhookController.handlePushEvent() 中呼叫。
     *
     * @param displayName  推送者顯示名稱
     * @param email        推送者 email（Azure DevOps uniqueName）
     * @param pushId       Azure DevOps push ID
     * @param branch       分支名稱
     * @param repoName     Repository 名稱（作為 project）
     * @param fileCount    變更檔案數
     */
    @Transactional
    public void recordPush(String displayName, String email, String pushId,
                           String branch, String repoName, int fileCount) {
        if (!config.isEnabled()) return;

        try {
            EngineerProfile engineer = findOrCreateEngineer(displayName, email);

            PushRecord record = PushRecord.builder()
                    .engineer(engineer)
                    .project(repoName)
                    .repositoryName(repoName)
                    .branch(branch)
                    .pushId(pushId)
                    .changedFileCount(fileCount)
                    .pushedAt(LocalDateTime.now())
                    .testOutcome(TestOutcome.PENDING)
                    .bugsFound(0)
                    .build();

            pushRecordRepo.save(record);
            log.info("已記錄 Push 事件：{} by {} (pushId={})", repoName, email, pushId);

        } catch (Exception e) {
            log.warn("記錄 Push 事件失敗（不影響主流程）：{}", e.getMessage());
        }
    }

    /**
     * 更新 Push 記錄的測試結果。
     * 在 E2ETestOrchestrator 測試完成後呼叫。
     *
     * @param pushId      Azure DevOps push ID
     * @param outcome     測試結果
     * @param bugsFound   發現的 bug 數量
     */
    @Transactional
    public void updateTestResult(String pushId, TestOutcome outcome, int bugsFound) {
        if (!config.isEnabled() || pushId == null) return;

        try {
            pushRecordRepo.findByPushId(pushId).ifPresent(record -> {
                record.setTestOutcome(outcome);
                record.setBugsFound(bugsFound);
                pushRecordRepo.save(record);
                log.info("已更新 Push 測試結果：pushId={}, outcome={}, bugs={}",
                        pushId, outcome, bugsFound);
            });
        } catch (Exception e) {
            log.warn("更新 Push 測試結果失敗（不影響主流程）：{}", e.getMessage());
        }
    }

    /**
     * 記錄一個 Bug。
     * 在 E2ETestOrchestrator.createWorkItemsForBugs() 中每建一個 WI 後呼叫。
     *
     * @param pushId      Azure DevOps push ID（關聯 PushRecord）
     * @param workItemId  Azure DevOps Work Item ID
     * @param severity    嚴重程度
     * @param title       Bug 標題
     */
    @Transactional
    public void recordBug(String pushId, int workItemId, String severity, String title) {
        if (!config.isEnabled()) return;

        try {
            PushRecord pushRecord = pushId != null
                    ? pushRecordRepo.findByPushId(pushId).orElse(null)
                    : null;

            EngineerProfile engineer = pushRecord != null ? pushRecord.getEngineer() : null;
            String project = pushRecord != null ? pushRecord.getProject() : "unknown";

            BugRecord bug = BugRecord.builder()
                    .pushRecord(pushRecord)
                    .engineer(engineer)
                    .project(project)
                    .workItemId(workItemId)
                    .bugTitle(title)
                    .severity(parseSeverity(severity))
                    .resolution(ResolutionMethod.UNRESOLVED)
                    .createdAt(LocalDateTime.now())
                    .build();

            bugRecordRepo.save(bug);
            log.info("已記錄 Bug：WI#{} - {} ({})", workItemId, title, severity);

        } catch (Exception e) {
            log.warn("記錄 Bug 失敗（不影響主流程）：{}", e.getMessage());
        }
    }

    /**
     * 記錄 AI 自動修復成功。
     * 在 AutoFixOrchestrator.handleReTestResult() 中 resolve 成功後呼叫。
     *
     * @param workItemId  被修復的 Work Item ID
     */
    @Transactional
    public void recordAiFixSuccess(int workItemId) {
        if (!config.isEnabled()) return;

        try {
            bugRecordRepo.findByWorkItemId(workItemId).ifPresent(bug -> {
                bug.setResolution(ResolutionMethod.AI_AUTO_FIX);
                bug.setResolvedAt(LocalDateTime.now());
                bugRecordRepo.save(bug);
                log.info("已記錄 AI 修復成功：WI#{}", workItemId);
            });
        } catch (Exception e) {
            log.warn("記錄 AI 修復成功失敗（不影響主流程）：{}", e.getMessage());
        }
    }

    /**
     * 取得某次 push 的 project 名稱（用於連續失敗檢查）。
     */
    public String getProjectByPushId(String pushId) {
        if (pushId == null) return null;
        return pushRecordRepo.findByPushId(pushId)
                .map(PushRecord::getProject)
                .orElse(null);
    }

    /**
     * 取得某次 push 的工程師 email（用於連續失敗檢查）。
     */
    public String getEngineerEmailByPushId(String pushId) {
        if (pushId == null) return null;
        return pushRecordRepo.findByPushId(pushId)
                .map(r -> r.getEngineer() != null ? r.getEngineer().getEmail() : null)
                .orElse(null);
    }

    // ========== Private Helpers ==========

    private EngineerProfile findOrCreateEngineer(String displayName, String email) {
        return engineerRepo.findByEmail(email).orElseGet(() -> {
            EngineerProfile newProfile = EngineerProfile.builder()
                    .email(email)
                    .displayName(displayName)
                    .firstSeenAt(LocalDateTime.now())
                    .lastActiveAt(LocalDateTime.now())
                    .build();
            return engineerRepo.save(newProfile);
        });
    }

    private Severity parseSeverity(String severity) {
        if (severity == null) return Severity.MEDIUM;
        try {
            return Severity.valueOf(severity.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 處理 "1 - Critical", "2 - High" 等格式
            String upper = severity.toUpperCase();
            if (upper.contains("CRITICAL")) return Severity.CRITICAL;
            if (upper.contains("HIGH")) return Severity.HIGH;
            if (upper.contains("LOW")) return Severity.LOW;
            return Severity.MEDIUM;
        }
    }
}
