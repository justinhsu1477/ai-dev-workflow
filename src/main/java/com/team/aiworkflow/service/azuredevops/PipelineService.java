package com.team.aiworkflow.service.azuredevops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service for interacting with Azure DevOps Pipelines REST API.
 * Retrieves build logs and test results from failed pipeline runs.
 */
@Service
@Slf4j
public class PipelineService {

    private final WebClient webClient;

    public PipelineService(@Qualifier("azureDevOpsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Get the build log for a specific build.
     * Fetches the timeline first, then retrieves logs from failed tasks.
     *
     * @param buildId The Azure DevOps build ID
     * @return Concatenated log content from failed tasks
     */
    public Mono<String> getTestLogs(int buildId) {
        log.info("Fetching test logs for build: {}", buildId);

        return getTimeline(buildId)
                .flatMap(timeline -> extractFailedLogIds(timeline, buildId))
                .doOnSuccess(logs -> log.info("Retrieved {} chars of test logs for build {}", logs.length(), buildId))
                .onErrorResume(e -> {
                    log.error("Failed to get test logs for build {}: {}", buildId, e.getMessage());
                    return Mono.just("Failed to retrieve build logs: " + e.getMessage());
                });
    }

    /**
     * Get the git diff (changes) for a specific commit/build.
     *
     * @param buildId The build ID to get changes for
     * @return Git diff content
     */
    public Mono<String> getBuildChanges(int buildId) {
        log.info("Fetching build changes for build: {}", buildId);

        return webClient.get()
                .uri("/_apis/build/builds/{buildId}/changes?api-version=7.1", buildId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(this::formatChanges)
                .onErrorResume(e -> {
                    log.error("Failed to get build changes: {}", e.getMessage());
                    return Mono.just("Failed to retrieve build changes");
                });
    }

    private Mono<Map<String, Object>> getTimeline(int buildId) {
        return webClient.get()
                .uri("/_apis/build/builds/{buildId}/timeline?api-version=7.1", buildId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Mono<String> extractFailedLogIds(Map<String, Object> timeline, int buildId) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) timeline.get("records");
        if (records == null || records.isEmpty()) {
            return Mono.just("No timeline records found");
        }

        // Find failed task records that have log references
        List<Integer> failedLogIds = records.stream()
                .filter(r -> "failed".equalsIgnoreCase((String) r.get("result")))
                .filter(r -> r.get("log") != null)
                .map(r -> {
                    Map<String, Object> logRef = (Map<String, Object>) r.get("log");
                    return (Integer) logRef.get("id");
                })
                .toList();

        if (failedLogIds.isEmpty()) {
            // Fallback: get logs from any task with errors
            return Mono.just("No failed task logs found in timeline");
        }

        // Fetch logs for each failed task and concatenate
        return Mono.zip(
                failedLogIds.stream()
                        .map(logId -> fetchLogContent(buildId, logId))
                        .toList(),
                results -> {
                    StringBuilder sb = new StringBuilder();
                    for (Object result : results) {
                        sb.append(result.toString()).append("\n---\n");
                    }
                    return sb.toString();
                }
        );
    }

    private Mono<String> fetchLogContent(int buildId, int logId) {
        return webClient.get()
                .uri("/_apis/build/builds/{buildId}/logs/{logId}?api-version=7.1", buildId, logId)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("Failed to fetch log " + logId + ": " + e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private String formatChanges(Map<String, Object> changesResponse) {
        List<Map<String, Object>> changes = (List<Map<String, Object>>) changesResponse.get("value");
        if (changes == null || changes.isEmpty()) {
            return "No changes found";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> change : changes) {
            sb.append("Commit: ").append(change.get("id")).append("\n");
            sb.append("Author: ").append(change.get("author")).append("\n");
            sb.append("Message: ").append(change.get("message")).append("\n\n");
        }
        return sb.toString();
    }
}
