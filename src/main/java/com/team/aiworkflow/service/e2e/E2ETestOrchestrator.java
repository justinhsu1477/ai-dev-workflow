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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.service.claude.ClaudeApiService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *         → 逐步執行 → 偵測 bug → 建立 Work Item（附截圖）→ 通知團隊
 *
 * 截圖流程：Playwright 截圖 → byte[] → 上傳 Azure DevOps 附件 → 關聯到 Bug Work Item
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class E2ETestOrchestrator {

    private final PlaywrightService playwrightService;
    private final AITestPlanner aiTestPlanner;
    private final WorkItemService workItemService;
    private final TeamsNotificationService teamsNotificationService;
    private final ClaudeApiService claudeApiService;
    private final ObjectMapper objectMapper;

    // ========== 精準範圍模式（Push 觸發） ==========

    /**
     * 非同步執行精準範圍的 AI E2E 測試。
     * 由 Push webhook 觸發，只測試受影響的模組。
     */
    @Async("aiTaskExecutor")
    public void runScopedTestAsync(E2ETestRequest request, TestScope scope) {
        log.info("啟動精準範圍 AI Test Agent：{} 個模組，{} 個測試流程",
                scope.getAffectedModuleIds().size(), scope.getTotalFlows());
        E2ETestResult result = runScopedTest(request, scope);
        log.info("精準範圍 AI Test Agent完成：{} - 發現 {} 個 bug",
                result.getStatus(), result.getBugsFound().size());
    }

    /**
     * 同步執行精準範圍的 AI E2E 測試。
     * 登入 → 依序測試各模組的測試流程 → 收集結果。
     */
    public E2ETestResult runScopedTest(E2ETestRequest request, TestScope scope) {
        String testRunId = UUID.randomUUID().toString().substring(0, 8);
        log.info("精準 AI Test Agent [{}] 開始：{} | 範圍：{}",
                testRunId, request.getAppUrl(), scope.getAffectedModuleNames());

        E2ETestResult result = E2ETestResult.builder()
                .testRunId(testRunId)
                .appUrl(request.getAppUrl())
                .appDescription(request.getAppDescription())
                .startedAt(LocalDateTime.now())
                .bugsFound(new ArrayList<>())
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
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[{}] 測試在流程 '{}' 逾時", testRunId, testFlow.getFlowName());
                    result.setStatus(E2ETestResult.TestRunStatus.TIMEOUT);
                    break;
                }

                log.info("[{}] === 開始測試流程：{} ({}) ===",
                        testRunId, testFlow.getFlowName(), testFlow.getRoute());

                String fullUrl = request.getAppUrl() + testFlow.getRoute();
                playwrightService.navigate(page, fullUrl);

                String pageContent = playwrightService.getAccessibilityTree(page);

                String flowContext = buildFlowContext(testFlow, scope.getScopeDescription());
                List<TestStep> flowSteps = aiTestPlanner.planTestSteps(
                        fullUrl, flowContext, pageContent,
                        Math.min(15, request.getMaxSteps()));

                if (flowSteps.isEmpty()) {
                    log.warn("[{}] AI 未為流程 '{}' 產生測試步驟", testRunId, testFlow.getFlowName());
                    continue;
                }

                log.info("[{}] 流程 '{}' 規劃了 {} 個步驟",
                        testRunId, testFlow.getFlowName(), flowSteps.size());

                List<TestStep> failedStepsInFlow = new ArrayList<>();

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

                    if (executedStep.getStatus() == TestStep.StepStatus.PASSED) {
                        passedCount++;
                        // 通過的步驟不需要保留截圖，釋放記憶體
                        executedStep.setScreenshotData(null);
                    } else if (executedStep.getStatus() == TestStep.StepStatus.FAILED) {
                        failedCount++;
                        failedStepsInFlow.add(executedStep);
                    }
                }

                // 流程結束後，將該流程所有失敗步驟整合為一個 bug（用 AI 歸納）
                if (!failedStepsInFlow.isEmpty()) {
                    recordConsolidatedBug(result, failedStepsInFlow, flowSteps, page, testFlow, testRunId);
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
                    "精準 AI Test Agent（%s）：%d/%d 步驟通過，測試 %d 個流程，發現 %d 個 bug | 受影響模組：%s",
                    scope.getTriggerType(),
                    passedCount, globalStepCounter,
                    scope.getTotalFlows(),
                    result.getBugsFound().size(),
                    String.join(", ", scope.getAffectedModuleNames())));

        } catch (Exception e) {
            log.error("[{}] 精準 AI Test Agent執行失敗：{}", testRunId, e.getMessage(), e);
            result.setStatus(E2ETestResult.TestRunStatus.ERROR);
            result.setSummary("測試執行錯誤：" + e.getMessage());
        } finally {
            if (page != null) page.close();
            if (context != null) context.close();
        }

        result.setCompletedAt(LocalDateTime.now());
        result.setTotalDurationMs(
                java.time.Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());

        // 建立 Work Item（附截圖）並通知團隊
        createWorkItemsForBugs(result);
        notifyTeam(result);

        log.info("[{}] 精準 AI Test Agent完成，耗時 {}ms：{}",
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
        log.info("啟動非同步 AI Test Agent：{}", request.getAppUrl());
        E2ETestResult result = runTest(request);
        log.info("AI Test Agent完成：{} - 發現 {} 個 bug",
                result.getStatus(), result.getBugsFound().size());
    }

    /**
     * 同步執行 AI E2E 測試並回傳結果（無範圍限制）。
     */
    public E2ETestResult runTest(E2ETestRequest request) {
        String testRunId = UUID.randomUUID().toString().substring(0, 8);
        log.info("AI Test Agent [{}] 開始：{}", testRunId, request.getAppUrl());

        E2ETestResult result = E2ETestResult.builder()
                .testRunId(testRunId)
                .appUrl(request.getAppUrl())
                .appDescription(request.getAppDescription())
                .startedAt(LocalDateTime.now())
                .bugsFound(new ArrayList<>())
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
            context = playwrightService.createSession();
            page = context.newPage();
            log.info("[{}] 瀏覽器工作階段已建立", testRunId);

            playwrightService.navigate(page, request.getAppUrl());
            String initialPageContent = playwrightService.getAccessibilityTree(page);
            log.info("[{}] 初始頁面已載入，正在規劃測試步驟...", testRunId);

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

                if (executedStep.getStatus() == TestStep.StepStatus.PASSED) {
                    passedCount++;
                    executedStep.setScreenshotData(null);
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
                            .screenshotData(executedStep.getScreenshotData())
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
                    "AI Test Agent：%d/%d 步驟通過，發現 %d 個 bug",
                    passedCount, plannedSteps.size(), result.getBugsFound().size()));

        } catch (Exception e) {
            log.error("[{}] AI Test Agent執行失敗：{}", testRunId, e.getMessage(), e);
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

        log.info("[{}] AI Test Agent完成，耗時 {}ms：{}",
                testRunId, result.getTotalDurationMs(), result.getSummary());

        return result;
    }

    // ========== 私有輔助方法 ==========

    /**
     * 執行登入流程。
     */
    private boolean performLogin(Page page, String appUrl, TestScope scope, String testRunId) {
        try {
            log.info("[{}] 開始登入流程，角色：{}", testRunId, scope.getTestRole());

            String loginUrl = appUrl + scope.getLoginUrl();
            playwrightService.navigate(page, loginUrl);
            playwrightService.waitForElement(page, scope.getLoginUsernameField(), 10000);

            // Vaadin LoginForm 使用 Web Components + Shadow DOM，
            // 需要用 JavaScript 直接操作內部 input 欄位，fill() 對 Vaadin 元件不可靠
            String username = scope.getLoginUsername();
            String password = scope.getLoginPassword();
            log.info("[{}] 填入帳號：{}", testRunId, username);

            // 用 JS 找到 Vaadin text-field 內部的 input 並設值
            page.evaluate("""
                (args) => {
                    const usernameField = document.querySelector(args.usernameSelector);
                    const passwordField = document.querySelector(args.passwordSelector);
                    if (usernameField) {
                        const uInput = usernameField.inputElement || usernameField.shadowRoot?.querySelector('input') || usernameField.querySelector('input');
                        if (uInput) { uInput.value = args.username; uInput.dispatchEvent(new Event('input', {bubbles: true})); uInput.dispatchEvent(new Event('change', {bubbles: true})); }
                        usernameField.value = args.username;
                    }
                    if (passwordField) {
                        const pInput = passwordField.inputElement || passwordField.shadowRoot?.querySelector('input') || passwordField.querySelector('input');
                        if (pInput) { pInput.value = args.password; pInput.dispatchEvent(new Event('input', {bubbles: true})); pInput.dispatchEvent(new Event('change', {bubbles: true})); }
                        passwordField.value = args.password;
                    }
                }
                """,
                    java.util.Map.of(
                            "usernameSelector", scope.getLoginUsernameField(),
                            "passwordSelector", scope.getLoginPasswordField(),
                            "username", username,
                            "password", password
                    ));

            // 等一下讓 Vaadin 同步狀態
            Thread.sleep(500);

            // 點擊登入按鈕
            page.locator(scope.getLoginSubmitButton()).first().click();

            // 等待頁面跳轉
            Thread.sleep(3000);

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
     * 將同一流程中的所有失敗步驟整合為一個 bug。
     * 用 AI 分析多個失敗步驟，歸納出根本原因，產生統一的 bug 描述。
     * 只建立一個 Work Item，而非每個失敗步驟各一個。
     */
    private void recordConsolidatedBug(E2ETestResult result, List<TestStep> failedSteps,
                                         List<TestStep> allSteps, Page page,
                                         ResolvedTestFlow flow, String testRunId) {
        String consoleErrors = playwrightService.getConsoleErrors(page);
        String currentUrl = playwrightService.getCurrentUrl(page);

        // 組裝所有步驟的執行摘要（給 AI 分析用）
        StringBuilder allStepsSummary = new StringBuilder();
        for (TestStep step : allSteps) {
            String status = step.getStatus() == TestStep.StepStatus.PASSED ? "✅" : "❌";
            allStepsSummary.append(String.format("步驟 %d [%s] %s: %s - %s\n",
                    step.getStepNumber(), status, step.getAction(),
                    step.getDescription(),
                    step.getErrorMessage() != null ? "錯誤: " + step.getErrorMessage() : "成功"));
        }

        // 組裝失敗步驟的詳細資訊
        StringBuilder failedDetails = new StringBuilder();
        for (TestStep step : failedSteps) {
            failedDetails.append(String.format("- 步驟 %d [%s]: %s\n  目標: %s\n  錯誤: %s\n",
                    step.getStepNumber(), step.getAction(), step.getDescription(),
                    step.getTarget(), step.getErrorMessage()));
        }

        // 用 AI 歸納失敗原因（結構化 JSON）
        BugAnalysis analysis = analyzeBugWithAI(flow, allStepsSummary.toString(), failedDetails.toString());

        // 取第一個失敗步驟的截圖
        byte[] screenshot = failedSteps.stream()
                .filter(s -> s.getScreenshotData() != null && s.getScreenshotData().length > 0)
                .findFirst()
                .map(TestStep::getScreenshotData)
                .orElse(null);

        // 決定嚴重程度
        String severity = failedSteps.stream()
                .map(this::determineSeverity)
                .reduce((a, b) -> "HIGH".equals(a) ? a : b)
                .orElse("MEDIUM");

        // 組合完整描述：人看的摘要 + 技術描述（給 AI 修 code 用）
        String fullDescription = String.format(
                "【問題摘要】\n%s\n\n【使用者影響】\n%s\n\n【技術描述】\n%s\n\n【建議修復方向】\n%s",
                analysis.summary, analysis.impact, analysis.technicalDetail, analysis.suggestedFix);

        E2ETestResult.BugFound bug = E2ETestResult.BugFound.builder()
                .title(String.format("[AI Test Agent] %s", analysis.title))
                .description(fullDescription)
                .severity(severity)
                .stepNumber(failedSteps.get(0).getStepNumber())
                .screenshotData(screenshot)
                .pageUrl(currentUrl)
                .consoleErrors(consoleErrors)
                .expectedBehavior(analysis.summary)
                .actualBehavior(analysis.technicalDetail)
                .build();

        result.getBugsFound().add(bug);
        log.warn("[{}] 流程 '{}' 發現 {} 個失敗步驟，已整合為 1 個 bug：{}",
                testRunId, flow.getFlowName(), failedSteps.size(), bug.getTitle());
    }

    /**
     * AI bug 分析結果的結構化容器。
     */
    private record BugAnalysis(String title, String summary, String technicalDetail,
                                String impact, String suggestedFix) {}

    /**
     * 用 AI 分析多個失敗步驟，歸納出結構化的 bug 描述。
     * 產出兩段式內容：人看得懂的摘要 + 可直接餵給 AI 修 code 的技術描述。
     */
    private BugAnalysis analyzeBugWithAI(ResolvedTestFlow flow,
                                           String allStepsSummary, String failedDetails) {
        String prompt = String.format("""
                你是一位資深 QA 工程師，正在分析自動化 E2E 測試的失敗結果。
                請歸納所有失敗步驟，判斷它們是否屬於同一個 bug，並產出結構化的 bug 報告。

                ## 測試流程
                模組：%s
                流程：%s
                說明：%s
                路由：%s

                ## 所有步驟執行結果
                %s

                ## 失敗步驟詳情
                %s

                ## 輸出要求

                請用以下 JSON 格式回答（不要加 markdown code block）：

                {
                  "title": "簡短的 bug 標題，不超過 30 字，用白話文描述問題核心，例如「D+2 訂貨儲存後數量未保留」",
                  "summary": "給非技術人員看的問題描述（2-3 句話）。用一般使用者能理解的語言描述：在什麼頁面、做了什麼操作、預期什麼結果、實際發生什麼問題。不要出現任何 CSS selector、HTML tag、timeout 等技術術語。",
                  "technicalDetail": "給開發人員和 AI 的技術描述。包含：(1) 問題發生的路由和頁面元件 (2) 完整的操作流程和每步結果 (3) 預期行為 vs 實際行為的具體差異 (4) 可能涉及的程式碼位置（根據路由和元件名稱推測）",
                  "impact": "這個 bug 對使用者的業務影響是什麼（1-2 句話）",
                  "suggestedFix": "開發人員應該從哪裡開始檢查、可能的修復方向（根據路由和功能推測可能的 Service/Component）"
                }
                """,
                flow.getModuleName(), flow.getFlowName(), flow.getDescription(), flow.getRoute(),
                allStepsSummary, failedDetails);

        try {
            String response = claudeApiService.analyze(prompt).block();
            if (response != null) {
                return parseBugAnalysis(response, flow);
            }
        } catch (Exception e) {
            log.warn("AI bug 分析失敗：{}", e.getMessage());
        }

        // fallback：AI 分析失敗時用預設描述
        return new BugAnalysis(
                flow.getFlowName() + " 測試失敗",
                String.format("「%s」功能的自動化測試未通過，請開發人員檢查。", flow.getFlowName()),
                failedDetails,
                "功能可能異常，影響使用者操作。",
                String.format("請檢查路由 %s 對應的程式碼。", flow.getRoute()));
    }

    /**
     * 解析 AI 回傳的 bug 分析 JSON。
     */
    private BugAnalysis parseBugAnalysis(String response, ResolvedTestFlow flow) {
        try {
            // 嘗試從回應中提取 JSON
            String json = response;
            int braceStart = response.indexOf('{');
            int braceEnd = response.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = response.substring(braceStart, braceEnd + 1);
            }

            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            return new BugAnalysis(
                    parsed.getOrDefault("title", flow.getFlowName() + " 測試失敗"),
                    parsed.getOrDefault("summary", "自動化測試未通過"),
                    parsed.getOrDefault("technicalDetail", "無技術描述"),
                    parsed.getOrDefault("impact", "功能可能異常"),
                    parsed.getOrDefault("suggestedFix", "請檢查相關程式碼"));

        } catch (Exception e) {
            log.warn("解析 AI bug 分析 JSON 失敗，使用原始回應：{}", e.getMessage());
            // JSON 解析失敗，把整段回應當 summary 用
            return new BugAnalysis(
                    flow.getFlowName() + " 測試失敗",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response,
                    response,
                    "功能可能異常，影響使用者操作。",
                    String.format("請檢查路由 %s 對應的程式碼。", flow.getRoute()));
        }
    }

    /**
     * 為每個發現的 bug 建立 Azure DevOps Work Item 並附加截圖。
     *
     * 流程：
     * 1. 建立 Bug Work Item
     * 2. 如果有截圖 → 上傳附件到 Azure DevOps
     * 3. 將附件關聯到 Work Item
     */
    private void createWorkItemsForBugs(E2ETestResult result) {
        for (E2ETestResult.BugFound bug : result.getBugsFound()) {
            try {
                com.team.aiworkflow.model.AnalysisResult analysisResult =
                        com.team.aiworkflow.model.AnalysisResult.builder()
                                .buildNumber(result.getBuildNumber())
                                .branch(result.getBranch())
                                .rootCause(bug.getExpectedBehavior())   // 人看的摘要
                                .suggestedFix(bug.getActualBehavior())  // 技術描述（給 AI 修 code）
                                .severity(mapSeverity(bug.getSeverity()))
                                .summary(bug.getTitle())
                                .build();

                // 步驟 1：建立 Bug Work Item
                Integer workItemId = workItemService.createBugFromAnalysis(analysisResult).block();
                if (workItemId != null) {
                    bug.setWorkItemId(workItemId);
                    log.info("已建立 Work Item #{} - E2E bug：{}", workItemId, bug.getTitle());

                    String attachmentUrl = null;

                    // 步驟 2：上傳截圖附件
                    if (bug.getScreenshotData() != null && bug.getScreenshotData().length > 0) {
                        try {
                            String fileName = String.format("e2e-%s-step%d.png",
                                    result.getTestRunId(), bug.getStepNumber());

                            attachmentUrl = workItemService
                                    .uploadAttachment(bug.getScreenshotData(), fileName)
                                    .block();

                            if (attachmentUrl != null) {
                                // 步驟 3：關聯附件到 Work Item
                                workItemService.attachToWorkItem(
                                        workItemId, attachmentUrl,
                                        String.format("AI Test Agent截圖 - 步驟 %d", bug.getStepNumber())
                                ).block();

                                bug.setAttachmentUrl(attachmentUrl);
                                log.info("截圖已附加到 Work Item #{}：{}", workItemId, fileName);
                            }
                        } catch (Exception e) {
                            log.warn("上傳截圖到 Work Item #{} 失敗：{}", workItemId, e.getMessage());
                        }

                        // 上傳完成後釋放記憶體
                        bug.setScreenshotData(null);
                    }

                    // 步驟 4：更新 ReproSteps，嵌入 E2E 測試詳細資訊和截圖
                    try {
                        String reproStepsHtml = buildE2EReproSteps(bug, result, attachmentUrl);
                        workItemService.updateReproSteps(workItemId, reproStepsHtml).block();
                        log.info("已更新 Work Item #{} 的 ReproSteps（含測試詳情和截圖）", workItemId);
                    } catch (Exception e) {
                        log.warn("更新 Work Item #{} ReproSteps 失敗：{}", workItemId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("建立 Work Item 失敗：{}", bug.getTitle(), e);
            }
        }
    }

    /**
     * 組裝 E2E 測試的 ReproSteps HTML，嵌入測試詳細資訊和截圖。
     * 分為「問題摘要」（PM/主管看）和「技術描述」（開發人員/AI 修 code 用）兩部分。
     * 這段 HTML 會顯示在 Azure DevOps Work Item 的「重現步驟」區塊中。
     */
    private String buildE2EReproSteps(E2ETestResult.BugFound bug,
                                       E2ETestResult result,
                                       String attachmentUrl) {
        StringBuilder sb = new StringBuilder();

        // === 第一部分：問題摘要（非技術人員也能看懂）===
        sb.append("<h2>問題摘要</h2>");
        sb.append(String.format("<p>%s</p>",
                bug.getExpectedBehavior() != null ? bug.getExpectedBehavior() : "自動化測試發現問題"));

        // 截圖放在最前面，讓人一眼看到問題
        if (attachmentUrl != null) {
            sb.append("<h3>問題截圖</h3>");
            sb.append(String.format("<p><img src=\"%s\" alt=\"AI Test Agent 截圖\" "
                            + "style=\"max-width:100%%; border:1px solid #ccc;\"></p>",
                    attachmentUrl));
        }

        // === 第二部分：測試資訊 ===
        sb.append("<h2>測試資訊</h2>");
        sb.append("<table border='1' cellpadding='5' cellspacing='0' style='border-collapse:collapse'>");
        sb.append(String.format("<tr><td><strong>測試執行 ID</strong></td><td>%s</td></tr>",
                result.getTestRunId() != null ? result.getTestRunId() : "N/A"));
        sb.append(String.format("<tr><td><strong>Build</strong></td><td>#%s</td></tr>",
                result.getBuildNumber() != null ? result.getBuildNumber() : "手動觸發"));
        sb.append(String.format("<tr><td><strong>分支</strong></td><td>%s</td></tr>",
                result.getBranch() != null ? result.getBranch() : "N/A"));
        sb.append(String.format("<tr><td><strong>測試環境</strong></td><td>%s</td></tr>",
                result.getAppUrl() != null ? result.getAppUrl() : "N/A"));
        sb.append(String.format("<tr><td><strong>嚴重程度</strong></td><td>%s</td></tr>",
                bug.getSeverity()));
        sb.append(String.format("<tr><td><strong>頁面 URL</strong></td><td>%s</td></tr>",
                bug.getPageUrl() != null ? bug.getPageUrl() : "N/A"));
        sb.append("</table>");

        // === 第三部分：操作流程（從 description 欄位解析出步驟） ===
        sb.append("<h2>操作流程</h2>");
        if (bug.getDescription() != null) {
            // description 格式為「【問題摘要】\n...\n\n【技術描述】\n...」
            // 提取各段落並以區塊呈現
            String desc = bug.getDescription();
            String[] sections = desc.split("【");
            for (String section : sections) {
                if (section.isBlank()) continue;
                int headerEnd = section.indexOf("】");
                if (headerEnd > 0) {
                    String header = section.substring(0, headerEnd);
                    String content = section.substring(headerEnd + 1).trim();
                    sb.append(String.format("<h3>%s</h3>", header));
                    // 將換行轉為 <br>
                    sb.append(String.format("<p>%s</p>", content.replace("\n", "<br>")));
                } else {
                    sb.append(String.format("<p>%s</p>", section.replace("\n", "<br>")));
                }
            }
        }

        // === 第四部分：技術描述（給開發人員和 AI 修 code 用）===
        sb.append("<h2>技術描述（供開發人員參考）</h2>");
        sb.append(String.format("<pre>%s</pre>",
                bug.getActualBehavior() != null ? bug.getActualBehavior() : "N/A"));

        // Console 錯誤
        if (bug.getConsoleErrors() != null && !bug.getConsoleErrors().isBlank()) {
            sb.append("<h3>瀏覽器 Console 錯誤</h3>");
            sb.append(String.format("<pre>%s</pre>", bug.getConsoleErrors()));
        }

        // 自動產生標註
        sb.append("<hr>");
        sb.append("<p><em>此 Work Item 由 AI Dev Workflow AI Test Agent 自動建立。</em></p>");

        return sb.toString();
    }

    /**
     * 透過 Teams 通知團隊測試結果。
     * 包含每個 bug 的簡短摘要，讓團隊成員在 Teams 就能看到問題概況。
     */
    private void notifyTeam(E2ETestResult result) {
        String emoji = switch (result.getStatus()) {
            case PASSED -> "✅";
            case FAILED -> "🔴";
            case TIMEOUT -> "⏱️";
            default -> "⚠️";
        };

        StringBuilder message = new StringBuilder();
        message.append(String.format(
                "%s **AI Test Agent 報告** - Build #%s\n\n",
                emoji,
                result.getBuildNumber() != null ? result.getBuildNumber() : "手動觸發"));

        message.append(String.format("步驟：%d/%d 通過 | 發現 bug：%d 個\n",
                result.getPassedSteps(), result.getTotalSteps(),
                result.getBugsFound().size()));

        // 列出每個 bug 的標題和摘要
        if (!result.getBugsFound().isEmpty()) {
            message.append("\n---\n");
            for (int i = 0; i < result.getBugsFound().size(); i++) {
                E2ETestResult.BugFound bug = result.getBugsFound().get(i);
                message.append(String.format("\n**Bug %d：%s**\n", i + 1, bug.getTitle()));
                // expectedBehavior 存的是 AI 產出的人看摘要
                if (bug.getExpectedBehavior() != null && !bug.getExpectedBehavior().isBlank()) {
                    message.append(bug.getExpectedBehavior()).append("\n");
                }
                if (bug.getWorkItemId() > 0) {
                    message.append(String.format("Work Item: #%d\n", bug.getWorkItemId()));
                }
            }
        }

        teamsNotificationService.sendSimpleMessage(message.toString()).subscribe();
    }

    /**
     * 根據失敗步驟的操作類型判斷嚴重程度。
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
