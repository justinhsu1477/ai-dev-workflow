package com.team.aiworkflow.service.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.model.e2e.TestStep;
import com.team.aiworkflow.service.azuredevops.WorkItemService;
import com.team.aiworkflow.service.e2e.TestScopeResolver.ResolvedTestFlow;
import com.team.aiworkflow.service.e2e.TestScopeResolver.TestScope;
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
 *
 * 支援兩種模式：
 * 1. 無範圍模式（原始模式）：AI 自由規劃測試步驟
 *    流程：啟動瀏覽器 → AI 規劃測試步驟 → 逐步執行 → 偵測 bug → 建立 Work Item → 通知團隊
 *
 * 2. 精準範圍模式（新增）：根據 git diff 分析結果，只測試受影響的模組
 *    流程：啟動瀏覽器 → 登入 → 依序執行各模組的測試流程 → AI 規劃每個流程的步驟
 *         → 逐步執行 → 偵測 bug → 建立 Work Item → 通知團隊
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class E2ETestOrchestrator {

    private final PlaywrightService playwrightService;
    private final AITestPlanner aiTestPlanner;
    private final WorkItemService workItemService;
    private final TeamsNotificationService teamsNotificationService;

    // ========== 精準範圍模式（Push 觸發） ==========

    /**
     * 非同步執行精準範圍的 AI E2E 測試。
     * 由 Push webhook 觸發，只測試受影響的模組。
     *
     * @param request 測試請求
     * @param scope   測試範圍（由 TestScopeResolver 解析）
     */
    @Async("aiTaskExecutor")
    public void runScopedTestAsync(E2ETestRequest request, TestScope scope) {
        log.info("啟動精準範圍 E2E 測試：{} 個模組，{} 個測試流程",
                scope.getAffectedModuleIds().size(), scope.getTotalFlows());
        E2ETestResult result = runScopedTest(request, scope);
        log.info("精準範圍 E2E 測試完成：{} - 發現 {} 個 bug",
                result.getStatus(), result.getBugsFound().size());
    }

    /**
     * 同步執行精準範圍的 AI E2E 測試。
     * 登入 → 依序測試各模組的測試流程 → 收集結果。
     *
     * @param request 測試請求
     * @param scope   測試範圍
     * @return 完整測試結果
     */
    public E2ETestResult runScopedTest(E2ETestRequest request, TestScope scope) {
        String testRunId = UUID.randomUUID().toString().substring(0, 8);
        log.info("精準 E2E 測試 [{}] 開始：{} | 範圍：{}",
                testRunId, request.getAppUrl(), scope.getAffectedModuleNames());

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
                .triggeredBy(request.getTriggeredBy())
                .build();

        int timeoutSeconds = request.getTimeoutSeconds() > 0 ? request.getTimeoutSeconds() : 300;
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        int globalStepCounter = 0;
        int passedCount = 0;
        int failedCount = 0;

        BrowserContext context = null;
        Page page = null;

        try {
            // 步驟 1：啟動瀏覽器
            context = playwrightService.createSession();
            page = context.newPage();
            log.info("[{}] 瀏覽器工作階段已建立", testRunId);

            // 步驟 2：執行登入流程
            boolean loginSuccess = performLogin(page, request.getAppUrl(), scope, testRunId);
            if (!loginSuccess) {
                log.error("[{}] 登入失敗，終止測試", testRunId);
                result.setStatus(E2ETestResult.TestRunStatus.ERROR);
                result.setSummary("登入失敗，無法執行測試");
                return result;
            }

            // 步驟 3：依序執行各測試流程
            for (ResolvedTestFlow testFlow : scope.getTestFlows()) {
                // 檢查是否逾時
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[{}] 測試在流程 '{}' 逾時", testRunId, testFlow.getFlowName());
                    result.setStatus(E2ETestResult.TestRunStatus.TIMEOUT);
                    break;
                }

                log.info("[{}] === 開始測試流程：{} ({}) ===",
                        testRunId, testFlow.getFlowName(), testFlow.getRoute());

                // 導航到測試流程的頁面
                String fullUrl = request.getAppUrl() + testFlow.getRoute();
                playwrightService.navigate(page, fullUrl);

                // 取得頁面狀態
                String pageContent = playwrightService.getAccessibilityTree(page);

                // AI 規劃此流程的測試步驟
                String flowContext = buildFlowContext(testFlow, scope.getScopeDescription());
                List<TestStep> flowSteps = aiTestPlanner.planTestSteps(
                        fullUrl, flowContext, pageContent,
                        Math.min(10, request.getMaxSteps())); // 每個流程最多 10 步

                if (flowSteps.isEmpty()) {
                    log.warn("[{}] AI 未為流程 '{}' 產生測試步驟", testRunId, testFlow.getFlowName());
                    continue;
                }

                log.info("[{}] 流程 '{}' 規劃了 {} 個步驟",
                        testRunId, testFlow.getFlowName(), flowSteps.size());

                // 逐步執行
                for (TestStep step : flowSteps) {
                    if (System.currentTimeMillis() > deadline) {
                        result.setStatus(E2ETestResult.TestRunStatus.TIMEOUT);
                        break;
                    }

                    globalStepCounter++;
                    step.setStepNumber(globalStepCounter);

                    log.info("[{}] 執行步驟 {}（{}）：{} - {}",
                            testRunId, globalStepCounter, testFlow.getFlowName(),
                            step.getAction(), step.getDescription());

                    TestStep executedStep = playwrightService.executeStep(page, step, testRunId);
                    result.getSteps().add(executedStep);

                    if (executedStep.getScreenshotPath() != null) {
                        result.getScreenshotPaths().add(executedStep.getScreenshotPath());
                    }

                    if (executedStep.getStatus() == TestStep.StepStatus.PASSED) {
                        passedCount++;
                    } else if (executedStep.getStatus() == TestStep.StepStatus.FAILED) {
                        failedCount++;
                        recordBug(result, executedStep, page, testFlow, testRunId);
                    }
                }

                if (result.getStatus() == E2ETestResult.TestRunStatus.TIMEOUT) break;

                log.info("[{}] === 流程 '{}' 完成 ===", testRunId, testFlow.getFlowName());
            }

            // 設定最終狀態
            result.setTotalSteps(globalStepCounter);
            result.setPassedSteps(passedCount);
            result.setFailedSteps(failedCount);

            if (result.getStatus() != E2ETestResult.TestRunStatus.TIMEOUT) {
                result.setStatus(failedCount > 0
                        ? E2ETestResult.TestRunStatus.FAILED
                        : E2ETestResult.TestRunStatus.PASSED);
            }

            result.setSummary(String.format(
                    "精準 E2E 測試（%s）：%d/%d 步驟通過，測試 %d 個流程，發現 %d 個 bug | 受影響模組：%s",
                    scope.getTriggerType(),
                    passedCount, globalStepCounter,
                    scope.getTotalFlows(),
                    result.getBugsFound().size(),
                    String.join(", ", scope.getAffectedModuleNames())));

        } catch (Exception e) {
            log.error("[{}] 精準 E2E 測試執行失敗：{}", testRunId, e.getMessage(), e);
            result.setStatus(E2ETestResult.TestRunStatus.ERROR);
            result.setSummary("測試執行錯誤：" + e.getMessage());
        } finally {
            if (page != null) page.close();
            if (context != null) context.close();
        }

        result.setCompletedAt(LocalDateTime.now());
        result.setTotalDurationMs(
                java.time.Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());

        // 建立 Work Item 並通知團隊
        createWorkItemsForBugs(result);
        notifyTeam(result);

        log.info("[{}] 精準 E2E 測試完成，耗時 {}ms：{}",
                testRunId, result.getTotalDurationMs(), result.getSummary());

        return result;
    }

    // ========== 無範圍模式（原始模式，保持向後相容） ==========

    /**
     * 非同步執行 AI E2E 測試（無範圍限制）。
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
     * 同步執行 AI E2E 測試並回傳結果（無範圍限制）。
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
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[{}] 測試在步驟 {} 逾時", testRunId, step.getStepNumber());
                    result.setStatus(E2ETestResult.TestRunStatus.TIMEOUT);
                    break;
                }

                log.info("[{}] 執行步驟 {}：{} - {}",
                        testRunId, step.getStepNumber(), step.getAction(), step.getDescription());

                TestStep executedStep = playwrightService.executeStep(page, step, testRunId);
                result.getSteps().add(executedStep);

                if (executedStep.getScreenshotPath() != null) {
                    result.getScreenshotPaths().add(executedStep.getScreenshotPath());
                }

                if (executedStep.getStatus() == TestStep.StepStatus.PASSED) {
                    passedCount++;
                } else if (executedStep.getStatus() == TestStep.StepStatus.FAILED) {
                    failedCount++;

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
            if (page != null) page.close();
            if (context != null) context.close();
        }

        result.setCompletedAt(LocalDateTime.now());
        result.setTotalDurationMs(
                java.time.Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());

        createWorkItemsForBugs(result);
        notifyTeam(result);

        log.info("[{}] E2E 測試完成，耗時 {}ms：{}",
                testRunId, result.getTotalDurationMs(), result.getSummary());

        return result;
    }

    // ========== 私有輔助方法 ==========

    /**
     * 執行登入流程。
     * 導航到登入頁面，填入帳號密碼，點擊登入按鈕。
     *
     * @return 登入是否成功
     */
    private boolean performLogin(Page page, String appUrl, TestScope scope, String testRunId) {
        try {
            log.info("[{}] 開始登入流程，角色：{}", testRunId, scope.getTestRole());

            // 導航到登入頁面
            String loginUrl = appUrl + scope.getLoginUrl();
            playwrightService.navigate(page, loginUrl);

            // 等待登入表單載入（最多等 10 秒）
            playwrightService.waitForElement(page, scope.getLoginUsernameField(), 10000);

            // 輸入帳號
            TestStep usernameStep = TestStep.builder()
                    .action(TestStep.Action.TYPE)
                    .target(scope.getLoginUsernameField())
                    .value(scope.getLoginUsername())
                    .description("輸入測試帳號")
                    .build();
            playwrightService.executeStep(page, usernameStep, testRunId);

            // 輸入密碼
            TestStep passwordStep = TestStep.builder()
                    .action(TestStep.Action.TYPE)
                    .target(scope.getLoginPasswordField())
                    .value(scope.getLoginPassword())
                    .description("輸入測試密碼")
                    .build();
            playwrightService.executeStep(page, passwordStep, testRunId);

            // 點擊登入按鈕
            TestStep submitStep = TestStep.builder()
                    .action(TestStep.Action.CLICK)
                    .target(scope.getLoginSubmitButton())
                    .description("點擊登入按鈕")
                    .build();
            playwrightService.executeStep(page, submitStep, testRunId);

            // 等待頁面跳轉（登入成功後會跳轉到首頁）
            Thread.sleep(2000); // 等待登入處理

            String currentUrl = playwrightService.getCurrentUrl(page);
            boolean success = !currentUrl.contains("/login");

            if (success) {
                log.info("[{}] 登入成功，當前頁面：{}", testRunId, currentUrl);
            } else {
                log.error("[{}] 登入失敗，仍在登入頁面：{}", testRunId, currentUrl);
            }

            return success;

        } catch (Exception e) {
            log.error("[{}] 登入過程發生錯誤：{}", testRunId, e.getMessage());
            return false;
        }
    }

    /**
     * 為測試流程建立上下文描述（提供給 AI Planner）。
     */
    private String buildFlowContext(ResolvedTestFlow flow, String scopeDescription) {
        return String.format("""
                你正在測試「%s」模組的「%s」功能。

                功能說明：%s
                測試路由：%s

                測試步驟提示：%s

                重要注意事項：
                - 這是一個 Vaadin 框架的應用程式，使用 Web Components 和 Shadow DOM
                - 優先使用語義化選擇器（如文字內容、role、aria-label）
                - 避免使用 class-only 的 CSS 選擇器（Vaadin 會動態生成 class 名稱）
                - 如果元素在 Shadow DOM 中，嘗試使用 Playwright 的 locator 搭配 text 定位
                - 不需要處理登入，已經登入完成
                """,
                flow.getModuleName(),
                flow.getFlowName(),
                flow.getDescription(),
                flow.getRoute(),
                flow.getStepsHint() != null ? flow.getStepsHint() : "無特定提示");
    }

    /**
     * 記錄測試中發現的 bug。
     */
    private void recordBug(E2ETestResult result, TestStep executedStep,
                            Page page, ResolvedTestFlow flow, String testRunId) {
        String consoleErrors = playwrightService.getConsoleErrors(page);
        String currentUrl = playwrightService.getCurrentUrl(page);

        E2ETestResult.BugFound bug = E2ETestResult.BugFound.builder()
                .title(String.format("[E2E][%s] %s", flow.getModuleName(), executedStep.getDescription()))
                .description(String.format(
                        "模組：%s\n流程：%s\n步驟 %d 失敗：%s\n操作：%s 目標 '%s'\n錯誤：%s",
                        flow.getModuleName(),
                        flow.getFlowName(),
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
        log.warn("[{}] 在流程 '{}' 步驟 {} 發現 bug：{}",
                testRunId, flow.getFlowName(), executedStep.getStepNumber(), bug.getTitle());
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
