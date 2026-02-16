package com.team.aiworkflow.controller;

import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.service.e2e.E2ETestOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for AI E2E testing endpoints.
 *
 * Provides both webhook-triggered and manual-triggered E2E test runs.
 */
@RestController
@RequestMapping("/api/e2e")
@Slf4j
@RequiredArgsConstructor
public class E2ETestController {

    private final E2ETestOrchestrator orchestrator;

    @Value("${workflow.e2e-testing.enabled:false}")
    private boolean e2eTestingEnabled;

    /**
     * Manually trigger an AI E2E test run.
     *
     * Example:
     * POST /api/e2e/run
     * {
     *   "appUrl": "https://staging.myapp.com",
     *   "appDescription": "CRUD user management system with create, edit, delete",
     *   "maxSteps": 20,
     *   "timeoutSeconds": 180
     * }
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> runTest(@RequestBody E2ETestRequest request) {
        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(Map.of("status", "disabled",
                    "message", "E2E testing is not enabled"));
        }

        log.info("Manual E2E test requested for: {}", request.getAppUrl());

        if (request.getAppUrl() == null || request.getAppUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "appUrl is required"));
        }

        request.setTriggeredBy("manual");
        orchestrator.runTestAsync(request);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "message", "E2E test started for " + request.getAppUrl()));
    }

    /**
     * Trigger E2E test synchronously and return full results.
     * Use this for testing/debugging the E2E module itself.
     */
    @PostMapping("/run-sync")
    public ResponseEntity<E2ETestResult> runTestSync(@RequestBody E2ETestRequest request) {
        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(E2ETestResult.builder()
                    .summary("E2E testing is not enabled")
                    .status(E2ETestResult.TestRunStatus.ERROR)
                    .build());
        }

        log.info("Sync E2E test requested for: {}", request.getAppUrl());
        E2ETestResult result = orchestrator.runTest(request);
        return ResponseEntity.ok(result);
    }
}
