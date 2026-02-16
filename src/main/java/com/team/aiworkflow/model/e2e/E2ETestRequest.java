package com.team.aiworkflow.model.e2e;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to trigger an AI E2E test run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class E2ETestRequest {

    private String appUrl;           // URL of the deployed application to test
    private String appDescription;   // Description of what the app does (helps AI plan tests)
    private String buildNumber;      // Optional: build number that triggered this
    private String branch;           // Optional: branch name
    private int maxSteps;            // Max test steps (default 30)
    private int timeoutSeconds;      // Max time for entire test run (default 300)
    private String triggeredBy;      // "manual", "deployment-webhook", etc.
}
