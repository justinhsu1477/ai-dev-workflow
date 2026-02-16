package com.team.aiworkflow.model.e2e;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single E2E test step planned or executed by the AI Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestStep {

    private int stepNumber;
    private Action action;
    private String target;       // CSS selector, URL, or input value
    private String value;        // For type actions: the text to type
    private String description;  // Human-readable description of this step
    private StepStatus status;
    private String screenshotPath;
    private String errorMessage;
    private long durationMs;

    public enum Action {
        NAVIGATE,   // Go to a URL
        CLICK,      // Click an element
        TYPE,       // Type text into an input
        SELECT,     // Select an option from dropdown
        ASSERT,     // Verify something on the page
        WAIT,       // Wait for an element or condition
        SCREENSHOT  // Take a screenshot
    }

    public enum StepStatus {
        PLANNED,    // Step is planned but not executed
        RUNNING,    // Step is currently executing
        PASSED,     // Step executed successfully
        FAILED,     // Step failed (possible bug found)
        SKIPPED     // Step was skipped
    }
}
