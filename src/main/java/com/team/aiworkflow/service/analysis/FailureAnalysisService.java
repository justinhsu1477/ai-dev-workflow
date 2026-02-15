package com.team.aiworkflow.service.analysis;

import com.team.aiworkflow.model.AnalysisResult;
import com.team.aiworkflow.model.dto.PipelineEvent;
import com.team.aiworkflow.service.azuredevops.PipelineService;
import com.team.aiworkflow.service.azuredevops.WorkItemService;
import com.team.aiworkflow.service.claude.ClaudeApiService;
import com.team.aiworkflow.service.claude.PromptBuilder;
import com.team.aiworkflow.service.claude.ResponseParser;
import com.team.aiworkflow.service.notification.TeamsNotificationService;
import com.team.aiworkflow.util.LogTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Module 1: Test Failure Analysis Service.
 *
 * Orchestrates the complete flow:
 * 1. Receive pipeline failure event
 * 2. Fetch test logs from Azure DevOps
 * 3. Fetch recent code changes
 * 4. Send to Claude API for analysis
 * 5. Create Bug work item with analysis
 * 6. Notify team via Teams
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FailureAnalysisService {

    private final PipelineService pipelineService;
    private final WorkItemService workItemService;
    private final ClaudeApiService claudeApiService;
    private final PromptBuilder promptBuilder;
    private final ResponseParser responseParser;
    private final TeamsNotificationService teamsNotificationService;
    private final LogTruncator logTruncator;

    @Value("${workflow.failure-analysis.branches:main,develop}")
    private String allowedBranches;

    /**
     * Process a pipeline failure event asynchronously.
     * This is the main entry point for Module 1.
     */
    @Async("aiTaskExecutor")
    public void processFailure(PipelineEvent event) {
        log.info("Processing pipeline failure for build #{} on branch {}",
                event.getResource().getBuildNumber(),
                event.getResource().getSourceBranch());

        // Check if this branch should be analyzed
        if (!isBranchAllowed(event.getResource().getSourceBranch())) {
            log.info("Branch {} is not in allowed list. Skipping analysis.",
                    event.getResource().getSourceBranch());
            return;
        }

        int buildId = event.getResource().getId();
        String buildNumber = event.getResource().getBuildNumber();
        String branch = event.getResource().getSourceBranch();
        String commitId = event.getResource().getSourceVersion();

        // Step 1 & 2: Fetch logs and changes in parallel
        Mono.zip(
                pipelineService.getTestLogs(buildId),
                pipelineService.getBuildChanges(buildId)
        )
        // Step 3: Truncate log and build prompt
        .flatMap(tuple -> {
            String rawLog = tuple.getT1();
            String changes = tuple.getT2();

            String truncatedLog = logTruncator.truncate(rawLog);
            String buildInfo = String.format("Build #%s | Branch: %s | Commit: %s",
                    buildNumber, branch, commitId);

            String prompt = promptBuilder.buildFailureAnalysisPrompt(truncatedLog, changes, buildInfo);

            log.info("Sending analysis request to Claude API ({} chars prompt)", prompt.length());

            // Step 4: Call Claude API
            return claudeApiService.analyze(prompt)
                    .map(aiResponse -> responseParser.parseFailureAnalysis(
                            aiResponse, String.valueOf(buildId), buildNumber, branch, commitId))
                    .map(result -> {
                        result.setAnalyzedAt(LocalDateTime.now());
                        result.setModelUsed("claude-sonnet-4-5");
                        return result;
                    });
        })
        // Step 5: Create Work Item
        .flatMap(result ->
            workItemService.createBugFromAnalysis(result)
                    .map(workItemId -> {
                        result.setWorkItemId(workItemId);
                        return result;
                    })
        )
        // Step 6: Send Teams notification
        .flatMap(result ->
            teamsNotificationService.notifyAnalysisResult(result)
                    .thenReturn(result)
        )
        .subscribe(
            result -> log.info("Analysis complete for build #{}. Created Work Item #{}",
                    result.getBuildNumber(), result.getWorkItemId()),
            error -> log.error("Failed to process pipeline failure for build #{}: {}",
                    buildNumber, error.getMessage())
        );
    }

    /**
     * Manually trigger analysis for a specific build (for testing/debugging).
     */
    public Mono<AnalysisResult> analyzeManually(int buildId, String buildNumber, String branch, String commitId) {
        return Mono.zip(
                pipelineService.getTestLogs(buildId),
                pipelineService.getBuildChanges(buildId)
        )
        .flatMap(tuple -> {
            String truncatedLog = logTruncator.truncate(tuple.getT1());
            String buildInfo = String.format("Build #%s | Branch: %s | Commit: %s",
                    buildNumber, branch, commitId);

            String prompt = promptBuilder.buildFailureAnalysisPrompt(truncatedLog, tuple.getT2(), buildInfo);

            return claudeApiService.analyze(prompt)
                    .map(aiResponse -> {
                        AnalysisResult result = responseParser.parseFailureAnalysis(
                                aiResponse, String.valueOf(buildId), buildNumber, branch, commitId);
                        result.setAnalyzedAt(LocalDateTime.now());
                        result.setModelUsed("claude-sonnet-4-5");
                        return result;
                    });
        });
    }

    private boolean isBranchAllowed(String branch) {
        if (allowedBranches == null || allowedBranches.isBlank()) return true;

        // Azure DevOps branch format: refs/heads/main
        String branchName = branch.replace("refs/heads/", "");

        List<String> allowed = Arrays.asList(allowedBranches.split(","));
        return allowed.stream()
                .map(String::trim)
                .anyMatch(b -> b.equals(branchName) || branch.endsWith("/" + b));
    }
}
