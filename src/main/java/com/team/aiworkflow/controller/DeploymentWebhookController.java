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
 * 接收 Azure DevOps「部署完成」Service Hook 事件。
 * 成功部署到 staging/dev 環境後，自動觸發 AI E2E 測試。
 *
 * 設定步驟：
 * 1. Azure DevOps → Project Settings → Service Hooks
 * 2. 建立 Subscription → Web Hooks
 * 3. 觸發條件：「Release deployment completed」且 Status = Succeeded
 * 4. URL：https://your-app/webhook/deployment-completed
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
     * 接收 Azure DevOps 的部署完成事件，觸發 E2E 測試。
     */
    @PostMapping("/deployment-completed")
    public ResponseEntity<Map<String, String>> handleDeploymentCompleted(
            @RequestBody(required = false) DeploymentEvent event) {

        log.info("收到部署完成事件");

        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(Map.of("status", "disabled"));
        }

        if (defaultStagingUrl == null || defaultStagingUrl.isBlank()) {
            log.warn("AI Test Agent已啟用，但未設定 staging URL");
            return ResponseEntity.ok(Map.of("status", "error",
                    "message", "staging-url 未設定"));
        }

        // 建立 E2E 測試請求
        E2ETestRequest request = E2ETestRequest.builder()
                .appUrl(defaultStagingUrl)
                .appDescription(defaultAppDescription)
                .buildNumber(event != null && event.getResource() != null
                        ? event.getResource().getReleaseName() : "unknown")
                .branch(event != null && event.getResource() != null
                        ? event.getResource().getEnvironmentName() : "staging")
                .maxSteps(30)
                .timeoutSeconds(300)
                .triggeredBy("deployment-webhook")
                .build();

        orchestrator.runTestAsync(request);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "message", "AI Test Agent已觸發：" + defaultStagingUrl));
    }

    /**
     * Azure DevOps Release Deployment 事件 DTO。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeploymentEvent {
        private String eventType;       // 事件類型
        private DeploymentResource resource;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DeploymentResource {
            private String releaseName;       // Release 名稱
            private String environmentName;   // 部署環境名稱
            private String status;            // 部署狀態
        }
    }
}
