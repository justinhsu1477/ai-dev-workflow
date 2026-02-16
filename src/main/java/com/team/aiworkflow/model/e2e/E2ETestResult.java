package com.team.aiworkflow.model.e2e;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The complete result of an AI E2E test run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class E2ETestResult {

    private String testRunId;
    private String appUrl;
    private String appDescription;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long totalDurationMs;

    private List<TestStep> steps;
    private List<BugFound> bugsFound;
    private List<String> screenshotPaths;

    private int totalSteps;
    private int passedSteps;
    private int failedSteps;
    private TestRunStatus status;
    private String summary;

    // Metadata
    private String triggeredBy;  // "deployment-webhook" or "manual"
    private String buildNumber;
    private String branch;

    public enum TestRunStatus {
        RUNNING,
        PASSED,      // All steps passed, no bugs found
        FAILED,      // Bugs were found
        ERROR,       // Test run itself failed (infra issue)
        TIMEOUT      // Test run exceeded time limit
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BugFound {
        private String title;
        private String description;
        private String severity;     // LOW, MEDIUM, HIGH, CRITICAL
        private int stepNumber;      // Which step discovered this bug
        private String screenshotPath;
        private String pageUrl;
        private String consoleErrors;
        private String expectedBehavior;
        private String actualBehavior;
        private int workItemId;      // Created Azure DevOps Work Item ID
    }
}
