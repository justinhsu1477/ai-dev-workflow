package com.team.aiworkflow.controller;

import com.team.aiworkflow.model.dto.PipelineEvent;
import com.team.aiworkflow.service.analysis.FailureAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST Controller for receiving Azure DevOps Service Hook webhooks.
 *
 * Azure DevOps Service Hook Configuration:
 * 1. Go to Project Settings → Service Hooks → Create Subscription
 * 2. Select "Web Hooks" as the service
 * 3. Trigger: "Build completed" with Status = Failed
 * 4. URL: https://your-app.azurewebsites.net/webhook/pipeline-failure
 */
@RestController
@RequestMapping("/webhook")
@Slf4j
@RequiredArgsConstructor
public class WebhookController {

    private final FailureAnalysisService failureAnalysisService;

    @Value("${workflow.failure-analysis.enabled:true}")
    private boolean failureAnalysisEnabled;

    /**
     * Receive pipeline build failure events from Azure DevOps.
     *
     * Azure DevOps sends a POST request when a build completes with failure status.
     * The event is processed asynchronously to respond quickly to the webhook.
     */
    @PostMapping("/pipeline-failure")
    public ResponseEntity<Map<String, String>> handlePipelineFailure(@RequestBody PipelineEvent event) {
        log.info("Received pipeline event: type={}, buildId={}, result={}",
                event.getEventType(),
                event.getResource() != null ? event.getResource().getId() : "null",
                event.getResource() != null ? event.getResource().getResult() : "null");

        if (!failureAnalysisEnabled) {
            log.info("Failure analysis is disabled. Ignoring event.");
            return ResponseEntity.ok(Map.of("status", "disabled"));
        }

        // Validate the event
        if (event.getResource() == null) {
            log.warn("Received pipeline event with no resource data");
            return ResponseEntity.badRequest().body(Map.of("error", "Missing resource data"));
        }

        // Only process failed builds
        String result = event.getResource().getResult();
        if (!"failed".equalsIgnoreCase(result)) {
            log.info("Build result is '{}', not 'failed'. Skipping.", result);
            return ResponseEntity.ok(Map.of("status", "skipped", "reason", "Build did not fail"));
        }

        // Process asynchronously (returns immediately to Azure DevOps)
        failureAnalysisService.processFailure(event);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "buildId", String.valueOf(event.getResource().getId()),
                "message", "Analysis triggered for build #" + event.getResource().getBuildNumber()
        ));
    }

    /**
     * Health check endpoint for Azure DevOps Service Hook validation.
     * Azure DevOps sends a test event when configuring the webhook.
     */
    @PostMapping("/pipeline-failure/test")
    public ResponseEntity<Map<String, String>> handleTestEvent(@RequestBody(required = false) Object body) {
        log.info("Received test event from Azure DevOps");
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Webhook is configured correctly"));
    }
}
