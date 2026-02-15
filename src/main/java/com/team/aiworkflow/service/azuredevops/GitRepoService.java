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
 * Service for interacting with Azure DevOps Git Repos.
 * Reads source code, diffs, and file contents from repositories.
 */
@Service
@Slf4j
public class GitRepoService {

    private final WebClient webClient;

    public GitRepoService(@Qualifier("azureDevOpsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Get the diff between two commits.
     *
     * @param repoId       Repository ID
     * @param baseCommitId Base commit
     * @param targetCommitId Target commit
     * @return Diff content as string
     */
    public Mono<String> getDiff(String repoId, String baseCommitId, String targetCommitId) {
        log.info("Fetching diff for repo {} between {} and {}", repoId, baseCommitId, targetCommitId);

        return webClient.get()
                .uri("/_apis/git/repositories/{repoId}/diffs/commits?baseVersion={base}&targetVersion={target}&api-version=7.1",
                        repoId, baseCommitId, targetCommitId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(this::formatDiff)
                .onErrorResume(e -> {
                    log.error("Failed to get diff: {}", e.getMessage());
                    return Mono.just("Failed to retrieve diff");
                });
    }

    /**
     * Get file content from a repository at a specific commit.
     *
     * @param repoId   Repository ID
     * @param filePath Path to the file in the repo
     * @param commitId Commit SHA (optional, defaults to latest)
     * @return File content
     */
    public Mono<String> getFileContent(String repoId, String filePath, String commitId) {
        log.info("Fetching file content: {} @ {}", filePath, commitId);

        String uri = commitId != null
                ? "/_apis/git/repositories/{repoId}/items?path={path}&versionType=commit&version={version}&api-version=7.1"
                : "/_apis/git/repositories/{repoId}/items?path={path}&api-version=7.1";

        WebClient.RequestHeadersSpec<?> request = commitId != null
                ? webClient.get().uri(uri, repoId, filePath, commitId)
                : webClient.get().uri(uri, repoId, filePath);

        return request
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> {
                    log.error("Failed to get file {}: {}", filePath, e.getMessage());
                    return Mono.just("Failed to retrieve file: " + filePath);
                });
    }

    /**
     * Get the list of changed files in a specific commit.
     */
    @SuppressWarnings("unchecked")
    public Mono<List<String>> getChangedFiles(String repoId, String commitId) {
        return webClient.get()
                .uri("/_apis/git/repositories/{repoId}/commits/{commitId}/changes?api-version=7.1",
                        repoId, commitId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    Map<String, Object> changeCounts = (Map<String, Object>) response.get("changeCounts");
                    List<Map<String, Object>> changes = (List<Map<String, Object>>) response.get("changes");
                    if (changes == null) return List.<String>of();
                    return changes.stream()
                            .map(c -> {
                                Map<String, Object> item = (Map<String, Object>) c.get("item");
                                return item != null ? (String) item.get("path") : "unknown";
                            })
                            .toList();
                })
                .onErrorResume(e -> {
                    log.error("Failed to get changed files: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    @SuppressWarnings("unchecked")
    private String formatDiff(Map<String, Object> diffResponse) {
        List<Map<String, Object>> changes = (List<Map<String, Object>>) diffResponse.get("changes");
        if (changes == null || changes.isEmpty()) {
            return "No changes found in diff";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> change : changes) {
            Map<String, Object> item = (Map<String, Object>) change.get("item");
            if (item != null) {
                sb.append(change.get("changeType")).append(": ").append(item.get("path")).append("\n");
            }
        }
        return sb.toString();
    }
}
