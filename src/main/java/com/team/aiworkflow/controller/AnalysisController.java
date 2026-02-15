package com.team.aiworkflow.controller;

import com.team.aiworkflow.model.AnalysisResult;
import com.team.aiworkflow.service.analysis.FailureAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST Controller for manually triggering AI analysis.
 * Useful for testing and debugging without waiting for pipeline events.
 */
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class AnalysisController {

    private final FailureAnalysisService failureAnalysisService;

    /**
     * Manually trigger failure analysis for a specific build.
     *
     * Example:
     * POST /api/analyze-failure
     * {
     *   "buildId": 123,
     *   "buildNumber": "20240115.1",
     *   "branch": "refs/heads/develop",
     *   "commitId": "abc123def456"
     * }
     */
    @PostMapping("/analyze-failure")
    public Mono<ResponseEntity<AnalysisResult>> analyzeFailure(@RequestBody Map<String, String> request) {
        int buildId = Integer.parseInt(request.getOrDefault("buildId", "0"));
        String buildNumber = request.getOrDefault("buildNumber", "unknown");
        String branch = request.getOrDefault("branch", "unknown");
        String commitId = request.getOrDefault("commitId", "");

        log.info("Manual analysis requested for build #{} ({})", buildNumber, buildId);

        return failureAnalysisService.analyzeManually(buildId, buildNumber, branch, commitId)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Manual analysis failed: {}", e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "AI Dev Workflow",
                "version", "0.0.1"
        ));
    }
}
