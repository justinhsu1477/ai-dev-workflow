package com.team.aiworkflow.service.autofix;

import com.team.aiworkflow.config.AutoFixConfig;
import com.team.aiworkflow.model.autofix.AutoFixResult;
import com.team.aiworkflow.model.autofix.AutoFixResult.AutoFixStatus;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.service.azuredevops.PullRequestService;
import com.team.aiworkflow.service.azuredevops.WorkItemService;
import com.team.aiworkflow.service.claude.ClaudeApiService;
import com.team.aiworkflow.service.claude.PromptBuilder;
import com.team.aiworkflow.service.stats.EngineerStatsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 自動修復主流程編排器。
 *
 * 階段 1（自動）：E2E 測試發現 Bug 後自動執行
 *   1. 根據 bug 找到相關原始碼
 *   2. 用 AI (Opus) 產生修復方案
 *   3. 建立 ai-fix/* 分支
 *   4. 套用修改 → commit + push
 *   5. 建立 PR（關聯 Work Item）
 *
 * 階段 2（手動觸發）：你在 IDE 切到 ai-fix/* 分支、重啟 app 後呼叫 re-test API
 *   1. 重跑 E2E 測試
 *   2. 通過 → 關閉 Work Item
 *   3. 失敗 → 加註失敗原因到 Work Item
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AutoFixOrchestrator {

    private final AutoFixConfig config;
    private final SourceCodeResolver sourceCodeResolver;
    private final FixApplicator fixApplicator;
    private final TargetRepoGitService gitService;
    private final PromptBuilder promptBuilder;
    private final ClaudeApiService claudeApiService;
    private final PullRequestService pullRequestService;
    private final WorkItemService workItemService;
    private final EngineerStatsRecorder engineerStatsRecorder;

    /**
     * 階段 1：嘗試自動修復 E2E 測試發現的 Bug。
     *
     * @param bug     E2E 測試發現的 bug
     * @param request 原始測試請求（包含 appUrl 等資訊）
     * @return 修復結果
     */
    public AutoFixResult attemptFix(E2ETestResult.BugFound bug, E2ETestRequest request) {
        long startTime = System.currentTimeMillis();
        int workItemId = bug.getWorkItemId();

        if (!config.isEnabled()) {
            log.info("Auto-fix 未啟用，跳過 Work Item #{}", workItemId);
            return AutoFixResult.builder()
                    .workItemId(workItemId)
                    .status(AutoFixStatus.DISABLED)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        log.info("=== 開始自動修復 Work Item #{} ===", workItemId);
        String branchName = null;

        try {
            // 步驟 1：找到相關原始碼
            Map<String, String> sourceFiles = sourceCodeResolver.resolveSourceFiles(bug);
            if (sourceFiles.isEmpty()) {
                log.warn("Work Item #{} 找不到相關原始碼", workItemId);
                addCommentSafe(workItemId, "[AI Auto-Fix] 找不到相關原始碼檔案，無法自動修復。");
                return buildResult(workItemId, AutoFixStatus.NO_SOURCE_FILES, null, null, null,
                        "找不到相關原始碼", startTime);
            }
            log.info("Work Item #{} 找到 {} 個相關原始碼檔案", workItemId, sourceFiles.size());

            // 步驟 2：組裝 prompt
            String codeContext = sourceCodeResolver.buildCodeContext(sourceFiles);
            String prompt = promptBuilder.buildE2EBugFixPrompt(bug, codeContext);
            log.info("Work Item #{} prompt 已組裝（{} chars）", workItemId, prompt.length());

            // 步驟 3：用 Opus 產生修復方案
            String aiResponse = claudeApiService.analyzeComplex(prompt).block();
            if (aiResponse == null || aiResponse.isBlank()) {
                log.error("Work Item #{} AI 未回傳修復方案", workItemId);
                addCommentSafe(workItemId, "[AI Auto-Fix] AI 未能產生修復方案。");
                return buildResult(workItemId, AutoFixStatus.AI_GENERATION_ERROR, null, null, null,
                        "AI 未回傳修復方案", startTime);
            }
            log.info("Work Item #{} AI 修復方案已產生（{} chars）", workItemId, aiResponse.length());

            // 步驟 4：解析修復方案
            FixApplicator.FixResult fixResult = fixApplicator.parseFixResponse(aiResponse);
            if (fixResult == null || fixResult.changes().isEmpty()) {
                log.error("Work Item #{} 無法解析 AI 修復方案", workItemId);
                addCommentSafe(workItemId, "[AI Auto-Fix] 無法解析 AI 的修復方案 JSON。");
                return buildResult(workItemId, AutoFixStatus.AI_GENERATION_ERROR, null, null, null,
                        "無法解析 AI 修復方案", startTime);
            }

            // 步驟 5：建立 fix 分支
            branchName = gitService.createFixBranch(workItemId);
            log.info("Work Item #{} 已建立分支：{}", workItemId, branchName);

            // 步驟 6：套用修改
            boolean applied = fixApplicator.applyChanges(fixResult);
            if (!applied) {
                log.error("Work Item #{} 套用修改失敗", workItemId);
                gitService.abandonBranch(branchName);
                addCommentSafe(workItemId, "[AI Auto-Fix] 無法將 AI 的修復方案套用到原始碼。");
                return buildResult(workItemId, AutoFixStatus.FIX_APPLY_ERROR, fixResult.fixDescription(),
                        branchName, null, "套用修改失敗", startTime);
            }

            // 步驟 7：commit + push
            String commitMessage = String.format("[AI Auto-Fix] %s (Work Item #%d)",
                    fixResult.fixDescription(), workItemId);
            gitService.commitAndPush(branchName, commitMessage);
            log.info("Work Item #{} 已 commit + push 到 {}", workItemId, branchName);

            // 步驟 8：建立 PR
            Integer prId = createPullRequestSafe(workItemId, branchName, fixResult);

            // 步驟 9：在 Work Item 加入修復註解
            String comment = String.format(
                    "[AI Auto-Fix] 已自動產生修復方案並建立 PR。\n\n" +
                    "**修復說明：** %s\n" +
                    "**分支：** %s\n" +
                    "**PR：** %s\n" +
                    "**說明：** %s\n\n" +
                    "請在 IDE 切到此分支、重啟應用程式後，呼叫 `POST /e2e/autofix/retest/%d` 觸發 re-test。",
                    fixResult.fixDescription(),
                    branchName,
                    prId != null ? "#" + prId : "建立失敗",
                    fixResult.explanation(),
                    workItemId);
            addCommentSafe(workItemId, comment);

            log.info("=== Work Item #{} 自動修復完成：分支 {}，PR #{} ===",
                    workItemId, branchName, prId);

            return buildResult(workItemId, AutoFixStatus.FIX_APPLIED, fixResult.fixDescription(),
                    branchName, prId, null, startTime);

        } catch (Exception e) {
            log.error("Work Item #{} 自動修復發生錯誤：{}", workItemId, e.getMessage(), e);
            addCommentSafe(workItemId,
                    String.format("[AI Auto-Fix] 自動修復過程發生錯誤：%s", e.getMessage()));
            return buildResult(workItemId, AutoFixStatus.ERROR, null, branchName, null,
                    e.getMessage(), startTime);
        } finally {
            // 確保本機回到 base branch
            try {
                gitService.abandonBranch(branchName != null ? branchName : "");
            } catch (Exception e) {
                log.warn("切回 base branch 失敗：{}", e.getMessage());
            }
        }
    }

    /**
     * 根據 re-test 結果更新 Work Item 狀態。
     * 由 AutoFixController 呼叫。
     */
    public AutoFixResult handleReTestResult(int workItemId, E2ETestResult testResult) {
        long startTime = System.currentTimeMillis();

        boolean passed = testResult.getBugsFound().isEmpty()
                && testResult.getStatus() == E2ETestResult.TestRunStatus.PASSED;

        if (passed) {
            log.info("Work Item #{} re-test 通過，準備關閉", workItemId);
            String comment = String.format(
                    "[AI Auto-Fix] Re-test 通過！測試 %d 個步驟全部成功。\nAI 自動修復已驗證有效，自動關閉此 Work Item。",
                    testResult.getTotalSteps());

            try {
                workItemService.resolveWorkItem(workItemId, comment).block();
                log.info("Work Item #{} 已自動關閉", workItemId);

                // 記錄 AI 修復成功（工程師績效追蹤）
                engineerStatsRecorder.recordAiFixSuccess(workItemId);
            } catch (Exception e) {
                log.error("關閉 Work Item #{} 失敗：{}", workItemId, e.getMessage());
            }

            return buildResult(workItemId, AutoFixStatus.VERIFIED, null, null, null, null, startTime);

        } else {
            log.warn("Work Item #{} re-test 失敗，發現 {} 個 bug",
                    workItemId, testResult.getBugsFound().size());
            String comment = String.format(
                    "[AI Auto-Fix] Re-test 失敗，AI 修復未能解決問題。\n" +
                    "測試結果：%d/%d 步驟通過，仍發現 %d 個 bug。\n需要人工介入修復。",
                    testResult.getPassedSteps(), testResult.getTotalSteps(),
                    testResult.getBugsFound().size());

            addCommentSafe(workItemId, comment);

            return buildResult(workItemId, AutoFixStatus.FIX_FAILED_TESTS, null, null, null,
                    String.format("Re-test 仍發現 %d 個 bug", testResult.getBugsFound().size()),
                    startTime);
        }
    }

    private Integer createPullRequestSafe(int workItemId, String branchName,
                                           FixApplicator.FixResult fixResult) {
        try {
            String repoId = config.getTargetRepoId();
            if (repoId == null || repoId.isBlank()) {
                log.warn("target-repo-id 未設定，跳過建立 PR");
                return null;
            }

            String title = String.format("[AI Auto-Fix] %s (WI #%d)", fixResult.fixDescription(), workItemId);
            String description = String.format(
                    "## AI 自動修復\n\n" +
                    "**修復說明：** %s\n\n" +
                    "**原因分析：** %s\n\n" +
                    "**驗證建議：** %s\n\n" +
                    "---\n*此 PR 由 AI Dev Workflow 自動產生。請在 re-test 通過後合併。*",
                    fixResult.fixDescription(), fixResult.explanation(), fixResult.testSuggestion());

            return pullRequestService.createPullRequest(
                    repoId, branchName, config.getBaseBranch(),
                    title, description, workItemId
            ).block();

        } catch (Exception e) {
            log.error("建立 PR 失敗（Work Item #{}）：{}", workItemId, e.getMessage());
            return null;
        }
    }

    private void addCommentSafe(int workItemId, String comment) {
        try {
            workItemService.addComment(workItemId, comment).block();
        } catch (Exception e) {
            log.warn("為 Work Item #{} 加入註解失敗：{}", workItemId, e.getMessage());
        }
    }

    private AutoFixResult buildResult(int workItemId, AutoFixStatus status,
                                       String fixDescription, String branchName,
                                       Integer prId, String failureReason, long startTime) {
        return AutoFixResult.builder()
                .workItemId(workItemId)
                .status(status)
                .fixDescription(fixDescription)
                .branchName(branchName)
                .pullRequestId(prId)
                .failureReason(failureReason)
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
