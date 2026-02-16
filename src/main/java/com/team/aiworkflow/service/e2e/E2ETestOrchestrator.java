package com.team.aiworkflow.service.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.model.e2e.TestStep;
import com.team.aiworkflow.service.azuredevops.WorkItemService;
import com.team.aiworkflow.service.notification.TeamsNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * E2E 測試流程編排器。
 * 完整流程：
 * 1. 啟動瀏覽器 → 2. AI 規劃測試步驟 → 3. 逐步執行 →
 * 4. 偵測 bug → 5. 建立 Work Item → 6. 通知團隊
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class E2ETestOrchestrator {

    private final PlaywrightService playwrightService;
    private final AITestPlanner aiTestPlanner;
    private final WorkItemService workItemService;
    private final TeamsNotificationService teamsNotificationService;

    /**
     * 非同步執行 AI E2E 測試。
     * 由部署 webhook 或手動 API 觸發的主要進入點。
     */
    @Async("aiTaskExecutor")
    public void runTestAsync(E2ETestRequest request) {
        log.info("啟動非同步 E2E 測試：{}", request.getAppUrl());
        E2ETestResult result = runTest(request);
        log.info("E2E 測試完成：{} - 發現 {} 個 bug",
                result.getStatus(), result.getBugsFound().size());
    }

    /**
     * 同步執行 AI E2E 測試並回傳結果。
     */
    public E2ETestResult runTest(E2ETestRequest request) {
        String testRunId = UUID.randomUUID().toString().substring(0, 8);
        log.info("E2E 測試 [{}] 開始：{}", testRunId, request.getAppUrl());

        E2ETestResult result = E2ETestResult.builder()
                .testRunId(testRunId)
                .appUrl(request.getAppUrl())
                .appDescription(request.getAppDescription())
                .startedAt(LocalDateTime.now())
                .bugsFound(new ArrayList<>())
                .screenshotPaths(new ArrayList<>())
                .steps(new ArrayList<>())
                .status(E2ETestResult.TestRunStatus.RUNNING)
                .buildNumber(request.getBuildNumber())
                .branch(request.getBranch())
                .build();

        int maxSteps = request.getMaxSteps() > 0 ? request.getMaxSteps() : 30;
        int timeoutSeconds = request.getTimeoutSeconds() > 0 ? request.getTimeoutSeconds() : 300;
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        BrowserContext context = null;
        Page page = null;

        try {
            // 步驟 1：啟動瀏覽器
            context = playwrightService.createSession();
            page = context.newPage();
            log.info("[{}] 瀏覽器工作階段已建立", testRunId);

            // 步驟 2：導航到應用程式並取得初始頁面狀態
            playwrightService.navigate(page, request.getAppUrl());
            String initialPageContent = playwrightService.getAccessibilityTree(page);
            log.info("[{}] 初始頁面已載入，正在規劃測試步驟...", testRunId);

            // 步驟 3：AI 規劃測試步驟
            List<TestStep> plannedSteps = aiTestPlanner.planTestSteps(
                    request.getAppUrl(),
                    request.getAppDescription(),
                    initialPageContent,
                    maxSteps);

            if (plannedSteps.isEmpty()) {
                log.warn("[{}] AI 未回傳任何測試步驟", testRunId);
                result.setStatus(E2ETestResult.TestRunStatus.ERROR);
                result.setSummary("AI 規劃測試步驟失敗");
                return result;
            }

            log.info("[{}] AI 規劃了 {} 個測試步驟", testRunId, plannedSteps.size());

            // 步驟 4：逐步執行
            int passedCount = 0;
            int failedCount = 0;

            for (TestStep step : plannedSteps) {
                // 檢查是否逾時
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[{}] 測試在步驟 {} 逾時", testRunId, step.getStepNumber());
                    result.setStatus(E2ETestResult.TestRunStatus.TIMEOUT);
                    break;
                }

                log.info("[{}] 執行步驟 {}：{} - {}",
                        testRunId, step.getStepNumber(), step.getAction(), step.getDescription());

                // 執行步驟
                TestStep executedStep = playwrightService.executeStep(page, step, testRunId);
                result.getSteps().add(executedStep);

                if (executedStep.getScreenshotPath() != null) {
                    result.getScreenshotPaths().add(executedStep.getScreenshotPath());
                }

                if (executedStep.getStatus() == TestStep.StepStatus.PASSED) {
                    passedCount++;
                } else if (executedStep.getStatus() == TestStep.StepStatus.FAILED) {
                    failedCount++;

                    // 步驟 5：分析失敗原因 — 是否為 bug？
                    String consoleErrors = playwrightService.getConsoleErrors(page);
                    String currentUrl = playwrightService.getCurrentUrl(page);

                    E2ETestResult.BugFound bug = E2ETestResult.BugFound.builder()
                            .title(String.format("[E2E] %s", executedStep.getDescription()))
                            .description(String.format(
                                    "步驟 %d 失敗：%s\n操作：%s 目標 '%s'\n錯誤：%s",
                                    executedStep.getStepNumber(),
                                    executedStep.getDescription(),
                                    executedStep.getAction(),
                                    executedStep.getTarget(),
                                    executedStep.getErrorMessage()))
                            .severity(determineSeverity(executedStep))
                            .stepNumber(executedStep.getStepNumber())
                            .screenshotPath(executedStep.getScreenshotPath())
                            .pageUrl(currentUrl)
                            .consoleErrors(consoleErrors)
                            .expectedBehavior(executedStep.getDescription())
                            .actualBehavior(executedStep.getErrorMessage())
                            .build();

                    result.getBugsFound().add(bug);
                    log.warn("[{}] 在步驟 {} 發現 bug：{}", testRunId, step.getStepNumber(), bug.getTitle());
                }
            }

            // 設定最終狀態
            result.setTotalSteps(plannedSteps.size());
            result.setPassedSteps(passedCount);
            result.setFailedSteps(failedCount);

            if (result.getStatus() != E2ETestResult.TestRunStatus.TIMEOUT) {
                result.setStatus(failedCount > 0
                        ? E2ETestResult.TestRunStatus.FAILED
                        : E2ETestResult.TestRunStatus.PASSED);
            }

            result.setSummary(String.format(
                    "E2E 測試：%d/%d 步驟通過，發現 %d 個 bug",
                    passedCount, plannedSteps.size(), result.getBugsFound().size()));

        } catch (Exception e) {
            log.error("[{}] E2E 測試執行失敗：{}", testRunId, e.getMessage(), e);
            result.setStatus(E2ETestResult.TestRunStatus.ERROR);
            result.setSummary("測試執行錯誤：" + e.getMessage());
        } finally {
            // 清理瀏覽器資源
            if (page != null) page.close();
            if (context != null) context.close();
        }

        result.setCompletedAt(LocalDateTime.now());
        result.setTotalDurationMs(
                java.time.Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());

        // 步驟 6：為發現的 bug 建立 Work Item 並通知團隊
        createWorkItemsForBugs(result);
        notifyTeam(result);

        log.info("[{}] E2E 測試完成，耗時 {}ms：{}",
                testRunId, result.getTotalDurationMs(), result.getSummary());

        return result;
    }

    /**
     * 為每個發現的 bug 建立 Azure DevOps Work Item。
     */
    private void createWorkItemsForBugs(E2ETestResult result) {
        for (E2ETestResult.BugFound bug : result.getBugsFound()) {
            try {
                com.team.aiworkflow.model.AnalysisResult analysisResult =
                        com.team.aiworkflow.model.AnalysisResult.builder()
                                .buildNumber(result.getBuildNumber())
                                .branch(result.getBranch())
                                .rootCause(bug.getDescription())
                                .suggestedFix("調查失敗的 E2E 步驟：" + bug.getExpectedBehavior())
                                .severity(mapSeverity(bug.getSeverity()))
                                .summary(bug.getTitle())
                                .build();

                Integer workItemId = workItemService.createBugFromAnalysis(analysisResult).block();
                if (workItemId != null) {
                    bug.setWorkItemId(workItemId);
                    log.info("已建立 Work Item #{} - E2E bug：{}", workItemId, bug.getTitle());
                }
            } catch (Exception e) {
                log.error("建立 Work Item 失敗：{}", bug.getTitle(), e);
            }
        }
    }

    /**
     * 透過 Teams 通知團隊測試結果。
     */
    private void notifyTeam(E2ETestResult result) {
        String emoji = switch (result.getStatus()) {
            case PASSED -> "✅";
            case FAILED -> "🔴";
            case TIMEOUT -> "⏱️";
            default -> "⚠️";
        };

        String message = String.format(
                "%s **E2E 測試報告** - Build #%s\n\n%s\n\n步驟：%d/%d 通過 | 發現 bug：%d 個",
                emoji,
                result.getBuildNumber() != null ? result.getBuildNumber() : "手動觸發",
                result.getSummary(),
                result.getPassedSteps(),
                result.getTotalSteps(),
                result.getBugsFound().size());

        teamsNotificationService.sendSimpleMessage(message).subscribe();
    }

    /**
     * 根據失敗步驟的操作類型判斷嚴重程度。
     * CLICK/NAVIGATE 失敗代表使用者無法執行關鍵操作，嚴重程度較高。
     */
    private String determineSeverity(TestStep failedStep) {
        return switch (failedStep.getAction()) {
            case CLICK, NAVIGATE -> "HIGH";
            case TYPE, SELECT -> "MEDIUM";
            case ASSERT, WAIT -> "MEDIUM";
            default -> "LOW";
        };
    }

    /**
     * 將字串嚴重程度轉換為 AnalysisResult.Severity 列舉。
     */
    private com.team.aiworkflow.model.AnalysisResult.Severity mapSeverity(String severity) {
        try {
            return com.team.aiworkflow.model.AnalysisResult.Severity.valueOf(severity);
        } catch (Exception e) {
            return com.team.aiworkflow.model.AnalysisResult.Severity.MEDIUM;
        }
    }
}
