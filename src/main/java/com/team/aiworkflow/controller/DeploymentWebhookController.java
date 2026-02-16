package com.team.aiworkflow.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.service.e2e.E2ETestOrchestrator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Azure DevOps "Release deployment completed" Service Hook events.
 * Triggers AI E2E testing after a successful deployment to staging/dev.
 *
 * Setup:
 * 1. Azure DevOps → Project Settings → Service Hooks
 * 2. Create Subscription → Web Hooks
 * 3. Trigger: "Release deployment completed" with Status = Succeeded
 * 4. URL: https://your-app/webhook/deployment-completed
 */
@RestController
@RequestMapping("/webhook")
@Slf4j
@RequiredArgsConstructor
public class DeploymentWebhookController {

    private final E2ETestOrchestrator orchestrator;

    @Value("${workflow.e2e-testing.enabled:false}")
    private boolean e2eTestingEnabled;

    @Value("${workflow.e2e-testing.staging-url:}")
    private String defaultStagingUrl;

    @Value("${workflow.e2e-testing.app-description:}")
    private String defaultAppDescription;

    /**
     * Receive deployment completed event from Azure DevOps.
     */
    @PostMapping("/deployment-completed")
    public ResponseEntity<Map<String, String>> handleDeploymentCompleted(
            @RequestBody(required = false) DeploymentEvent event) {

        log.info("Received deployment completed event");

        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(Map.of("status", "disabled"));
        }

        if (defaultStagingUrl == null || defaultStagingUrl.isBlank()) {
            log.warn("E2E testing enabled but staging URL not configured");
            return ResponseEntity.ok(Map.of("status", "error",
                    "message", "staging-url not configured"));
        }

        // Build E2E test request
        E2ETestRequest request = E2ETestRequest.builder()
                .appUrl(defaultStagingUrl)
                .appDescription(defaultAppDescription)
                .buildNumber(event != null && event.getResource() != null
                        ? event.getResource().getReleaseName() : "unknown")
                .branch(event != null && event.getResource() != null
                        ? event.getResource().getEnvironmentName() : "staging")
                .maxSteps(30)
                .timeoutSeconds(300)
                .build();

        orchestrator.runTestAsync(request);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "message", "E2E test triggered for " + defaultStagingUrl));
    }

    /**
     * DTO for Azure DevOps Release Deployment event.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeploymentEvent {
        private String eventType;
        private DeploymentResource resource;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DeploymentResource {
            private String releaseName;
            private String environmentName;
            private String status;
        }
    }
}
