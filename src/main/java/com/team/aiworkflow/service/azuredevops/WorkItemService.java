package com.team.aiworkflow.service.azuredevops;

import com.team.aiworkflow.model.AnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service for managing Azure DevOps Work Items.
 * Creates Bug work items with AI analysis results.
 */
@Service
@Slf4j
public class WorkItemService {

    private final WebClient webClient;

    public WorkItemService(@Qualifier("azureDevOpsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Create a Bug work item from an AI analysis result.
     *
     * @param result The AI analysis result
     * @return The created work item ID
     */
    public Mono<Integer> createBugFromAnalysis(AnalysisResult result) {
        log.info("Creating Bug work item for build: {}", result.getBuildNumber());

        String title = String.format("[AI] %s - Build #%s", result.getSummary(), result.getBuildNumber());
        String description = formatDescription(result);

        // Azure DevOps uses JSON Patch format for creating work items
        List<Map<String, Object>> patchDocument = List.of(
                createPatchOp("/fields/System.Title", truncate(title, 255)),
                createPatchOp("/fields/System.Description", description),
                createPatchOp("/fields/Microsoft.VSTS.Common.Severity",
                        mapSeverity(result.getSeverity())),
                createPatchOp("/fields/System.Tags", "AI-Analyzed"),
                createPatchOp("/fields/Microsoft.VSTS.TCM.ReproSteps",
                        formatReproSteps(result))
        );

        return webClient.post()
                .uri("/_apis/wit/workitems/$Bug?api-version=7.1")
                .contentType(MediaType.valueOf("application/json-patch+json"))
                .bodyValue(patchDocument)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> (Integer) response.get("id"))
                .doOnSuccess(id -> log.info("Created Bug work item #{} for build {}", id, result.getBuildNumber()))
                .doOnError(e -> log.error("Failed to create work item: {}", e.getMessage()));
    }

    /**
     * Get details of a work item by ID.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getWorkItem(int workItemId) {
        return webClient.get()
                .uri("/_apis/wit/workitems/{id}?api-version=7.1&$expand=all", workItemId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> createPatchOp(String path, Object value) {
        return Map.of(
                "op", "add",
                "path", path,
                "value", value
        );
    }

    private String formatDescription(AnalysisResult result) {
        return String.format("""
                <h2>AI Analysis Report</h2>
                <p><strong>Build:</strong> %s (Branch: %s)</p>
                <p><strong>Commit:</strong> %s</p>
                <p><strong>Severity:</strong> %s</p>

                <h3>Root Cause</h3>
                <p>%s</p>

                <h3>Suggested Fix</h3>
                <pre>%s</pre>

                <h3>Affected Files</h3>
                <ul>%s</ul>

                <hr>
                <p><em>This work item was automatically created by AI Dev Workflow.</em></p>
                """,
                result.getBuildNumber(),
                result.getBranch(),
                result.getCommitId() != null ? result.getCommitId() : "N/A",
                result.getSeverity(),
                result.getRootCause(),
                result.getSuggestedFix() != null ? result.getSuggestedFix() : "No suggestion",
                formatAffectedFiles(result.getAffectedFiles())
        );
    }

    private String formatReproSteps(AnalysisResult result) {
        return String.format("""
                <h3>How to Reproduce</h3>
                <ol>
                  <li>Check out branch: %s</li>
                  <li>Run the CI pipeline</li>
                  <li>Observe test failure in build #%s</li>
                </ol>
                <h3>Root Cause</h3>
                <p>%s</p>
                """,
                result.getBranch(),
                result.getBuildNumber(),
                result.getRootCause()
        );
    }

    private String formatAffectedFiles(List<String> files) {
        if (files == null || files.isEmpty()) return "<li>Unknown</li>";
        StringBuilder sb = new StringBuilder();
        for (String file : files) {
            sb.append("<li>").append(file).append("</li>");
        }
        return sb.toString();
    }

    private String mapSeverity(AnalysisResult.Severity severity) {
        if (severity == null) return "3 - Medium";
        return switch (severity) {
            case CRITICAL -> "1 - Critical";
            case HIGH -> "2 - High";
            case MEDIUM -> "3 - Medium";
            case LOW -> "4 - Low";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength - 3) + "..." : text;
    }
}
