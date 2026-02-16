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
 * Orchestrates the complete AI E2E test flow:
 * 1. Launch browser → 2. AI plans test steps → 3. Execute steps →
 * 4. Detect bugs → 5. Create Work Items → 6. Notify team
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
     * Run an AI E2E test asynchronously.
     * This is the main entry point triggered by deployment webhook or manual API.
     */
    @Async("aiTaskExecutor")
    public void runTestAsync(E2ETestRequest request) {
        log.info("Starting async E2E test for: {}", request.getAppUrl());
        E2ETestResult result = runTest(request);
        log.info("E2E test completed: {} - {} bugs found",
                result.getStatus(), result.getBugsFound().size());
    }

    /**
     * Run an AI E2E test synchronously and return the result.
     */
    public E2ETestResult runTest(E2ETestRequest request) {
        String testRunId = UUID.randomUUID().toString().substring(0, 8);
        log.info("E2E Test Run [{}] starting for: {}", testRunId, request.getAppUrl());

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
            // Step 1: Launch browser
            context = playwrightService.createSession();
            page = context.newPage();
            log.info("[{}] Browser session created", testRunId);

            // Step 2: Navigate to app and get initial page state
            playwrightService.navigate(page, request.getAppUrl());
            String initialPageContent = playwrightService.getAccessibilityTree(page);
            log.info("[{}] Initial page loaded, planning test steps...", testRunId);

            // Step 3: AI plans test steps
            List<TestStep> plannedSteps = aiTestPlanner.planTestSteps(
                    request.getAppUrl(),
                    request.getAppDescription(),
                    initialPageContent,
                    maxSteps);

            if (plannedSteps.isEmpty()) {
                log.warn("[{}] AI returned no test steps", testRunId);
                result.setStatus(E2ETestResult.TestRunStatus.ERROR);
                result.setSummary("AI failed to plan test steps");
                return result;
            }

            log.info("[{}] AI planned {} test steps", testRunId, plannedSteps.size());

            // Step 4: Execute each step
            int passedCount = 0;
            int failedCount = 0;

            for (TestStep step : plannedSteps) {
                // Check timeout
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[{}] Test run timed out at step {}", testRunId, step.getStepNumber());
                    result.setStatus(E2ETestResult.TestRunStatus.TIMEOUT);
                    break;
                }

                log.info("[{}] Executing step {}: {} - {}",
                        testRunId, step.getStepNumber(), step.getAction(), step.getDescription());

                // Execute the step
                TestStep executedStep = playwrightService.executeStep(page, step, testRunId);
                result.getSteps().add(executedStep);

                if (executedStep.getScreenshotPath() != null) {
                    result.getScreenshotPaths().add(executedStep.getScreenshotPath());
                }

                if (executedStep.getStatus() == TestStep.StepStatus.PASSED) {
                    passedCount++;
                } else if (executedStep.getStatus() == TestStep.StepStatus.FAILED) {
                    failedCount++;

                    // Step 5: Analyze the failure - is it a bug?
                    String consoleErrors = playwrightService.getConsoleErrors(page);
                    String currentUrl = playwrightService.getCurrentUrl(page);

                    E2ETestResult.BugFound bug = E2ETestResult.BugFound.builder()
                            .title(String.format("[E2E] %s", executedStep.getDescription()))
                            .description(String.format(
                                    "Step %d failed: %s\nAction: %s on '%s'\nError: %s",
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
                    log.warn("[{}] Bug found at step {}: {}", testRunId, step.getStepNumber(), bug.getTitle());
                }
            }

            // Set final status
            result.setTotalSteps(plannedSteps.size());
            result.setPassedSteps(passedCount);
            result.setFailedSteps(failedCount);

            if (result.getStatus() != E2ETestResult.TestRunStatus.TIMEOUT) {
                result.setStatus(failedCount > 0
                        ? E2ETestResult.TestRunStatus.FAILED
                        : E2ETestResult.TestRunStatus.PASSED);
            }

            result.setSummary(String.format(
                    "E2E Test: %d/%d steps passed, %d bugs found",
                    passedCount, plannedSteps.size(), result.getBugsFound().size()));

        } catch (Exception e) {
            log.error("[{}] E2E test run failed: {}", testRunId, e.getMessage(), e);
            result.setStatus(E2ETestResult.TestRunStatus.ERROR);
            result.setSummary("Test run error: " + e.getMessage());
        } finally {
            // Cleanup browser
            if (page != null) page.close();
            if (context != null) context.close();
        }

        result.setCompletedAt(LocalDateTime.now());
        result.setTotalDurationMs(
                java.time.Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());

        // Step 6: Create Work Items for bugs and notify
        createWorkItemsForBugs(result);
        notifyTeam(result);

        log.info("[{}] E2E test completed in {}ms: {}",
                testRunId, result.getTotalDurationMs(), result.getSummary());

        return result;
    }

    private void createWorkItemsForBugs(E2ETestResult result) {
        for (E2ETestResult.BugFound bug : result.getBugsFound()) {
            try {
                com.team.aiworkflow.model.AnalysisResult analysisResult =
                        com.team.aiworkflow.model.AnalysisResult.builder()
                                .buildNumber(result.getBuildNumber())
                                .branch(result.getBranch())
                                .rootCause(bug.getDescription())
                                .suggestedFix("Investigate the failed E2E step: " + bug.getExpectedBehavior())
                                .severity(mapSeverity(bug.getSeverity()))
                                .summary(bug.getTitle())
                                .build();

                Integer workItemId = workItemService.createBugFromAnalysis(analysisResult).block();
                if (workItemId != null) {
                    bug.setWorkItemId(workItemId);
                    log.info("Created Work Item #{} for E2E bug: {}", workItemId, bug.getTitle());
                }
            } catch (Exception e) {
                log.error("Failed to create Work Item for bug: {}", bug.getTitle(), e);
            }
        }
    }

    private void notifyTeam(E2ETestResult result) {
        String emoji = switch (result.getStatus()) {
            case PASSED -> "✅";
            case FAILED -> "🔴";
            case TIMEOUT -> "⏱️";
            default -> "⚠️";
        };

        String message = String.format(
                "%s **E2E Test Report** - Build #%s\n\n%s\n\nSteps: %d/%d passed | Bugs found: %d",
                emoji,
                result.getBuildNumber() != null ? result.getBuildNumber() : "manual",
                result.getSummary(),
                result.getPassedSteps(),
                result.getTotalSteps(),
                result.getBugsFound().size());

        teamsNotificationService.sendSimpleMessage(message).subscribe();
    }

    private String determineSeverity(TestStep failedStep) {
        // CLICK or NAVIGATE failures are higher severity (user can't perform actions)
        return switch (failedStep.getAction()) {
            case CLICK, NAVIGATE -> "HIGH";
            case TYPE, SELECT -> "MEDIUM";
            case ASSERT, WAIT -> "MEDIUM";
            default -> "LOW";
        };
    }

    private com.team.aiworkflow.model.AnalysisResult.Severity mapSeverity(String severity) {
        try {
            return com.team.aiworkflow.model.AnalysisResult.Severity.valueOf(severity);
        } catch (Exception e) {
            return com.team.aiworkflow.model.AnalysisResult.Severity.MEDIUM;
        }
    }
}
