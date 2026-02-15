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
 * Service for managing Azure DevOps Pull Requests.
 * Creates PRs and adds comments for AI-generated content.
 */
@Service
@Slf4j
public class PullRequestService {

    private final WebClient webClient;

    public PullRequestService(@Qualifier("azureDevOpsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Create a pull request.
     */
    public Mono<Integer> createPullRequest(String repoId, String sourceBranch, String targetBranch,
                                            String title, String description, int workItemId) {
        log.info("Creating PR: {} -> {} in repo {}", sourceBranch, targetBranch, repoId);

        Map<String, Object> prBody = Map.of(
                "sourceRefName", "refs/heads/" + sourceBranch,
                "targetRefName", "refs/heads/" + targetBranch,
                "title", title,
                "description", description,
                "labels", List.of(Map.of("name", "ai-generated")),
                "workItemRefs", List.of(Map.of("id", String.valueOf(workItemId)))
        );

        return webClient.post()
                .uri("/_apis/git/repositories/{repoId}/pullrequests?api-version=7.1", repoId)
                .bodyValue(prBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> (Integer) response.get("pullRequestId"))
                .doOnSuccess(prId -> log.info("Created PR #{}", prId))
                .doOnError(e -> log.error("Failed to create PR: {}", e.getMessage()));
    }

    /**
     * Add a comment thread to a pull request.
     * Used for AI-generated test suggestions.
     */
    public Mono<Void> addComment(String repoId, int pullRequestId, String commentContent) {
        log.info("Adding comment to PR #{}", pullRequestId);

        Map<String, Object> thread = Map.of(
                "comments", List.of(
                        Map.of("content", commentContent, "commentType", "text")
                ),
                "status", "active"
        );

        return webClient.post()
                .uri("/_apis/git/repositories/{repoId}/pullRequests/{prId}/threads?api-version=7.1",
                        repoId, pullRequestId)
                .bodyValue(thread)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("Added comment to PR #{}", pullRequestId))
                .doOnError(e -> log.error("Failed to add comment to PR #{}: {}", pullRequestId, e.getMessage()));
    }

    /**
     * Get pull request details.
     */
    public Mono<Map<String, Object>> getPullRequest(String repoId, int pullRequestId) {
        return webClient.get()
                .uri("/_apis/git/repositories/{repoId}/pullRequests/{prId}?api-version=7.1",
                        repoId, pullRequestId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * Get the diff/changes in a pull request.
     */
    public Mono<String> getPullRequestDiff(String repoId, int pullRequestId) {
        return webClient.get()
                .uri("/_apis/git/repositories/{repoId}/pullRequests/{prId}/iterations?api-version=7.1",
                        repoId, pullRequestId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(this::formatIterations)
                .onErrorResume(e -> Mono.just("Failed to get PR diff: " + e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private String formatIterations(Map<String, Object> response) {
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) response.get("value");
        if (iterations == null || iterations.isEmpty()) {
            return "No iterations found";
        }
        return "PR has " + iterations.size() + " iteration(s)";
    }
}
