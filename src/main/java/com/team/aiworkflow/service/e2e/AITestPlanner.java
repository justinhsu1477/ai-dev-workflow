package com.team.aiworkflow.service.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.model.e2e.TestStep;
import com.team.aiworkflow.service.claude.ClaudeApiService;
import com.team.aiworkflow.service.claude.ResponseParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses Claude API to plan E2E test steps based on the application description
 * and current page state. The AI acts as a QA engineer deciding what to test.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AITestPlanner {

    private final ClaudeApiService claudeApiService;
    private final ObjectMapper objectMapper;

    /**
     * Generate initial test plan based on app description.
     * AI decides what user flows to test (e.g., CRUD operations).
     */
    public List<TestStep> planTestSteps(String appUrl, String appDescription,
                                         String pageContent, int maxSteps) {
        String prompt = String.format("""
                You are a senior QA engineer planning E2E tests for a web application.

                ## Application Info
                - URL: %s
                - Description: %s

                ## Current Page State
                %s

                ## Your Task
                Plan a series of test steps to verify the application works correctly.
                Focus on CRITICAL user flows:
                1. Can the user navigate to all main pages?
                2. Can the user perform CRUD operations? (Create, Read, Update, Delete)
                3. Do forms submit correctly?
                4. Do buttons respond to clicks?
                5. Does data appear/update after operations?

                Generate a maximum of %d test steps.

                Respond ONLY in this JSON format:
                ```json
                {
                  "steps": [
                    {"action": "NAVIGATE", "target": "/", "description": "Navigate to home page"},
                    {"action": "CLICK", "target": "button#add-new", "description": "Click add new button"},
                    {"action": "ASSERT", "target": "form.create-form", "description": "Verify form appears"},
                    {"action": "TYPE", "target": "input[name='name']", "value": "Test User", "description": "Enter name"},
                    {"action": "CLICK", "target": "button[type='submit']", "description": "Submit the form"},
                    {"action": "ASSERT", "target": ".success-message", "description": "Verify success message appears"}
                  ]
                }
                ```

                Rules for selectors:
                - Prefer: id selectors (#btn-add), data-testid, name attributes
                - Then: semantic selectors (button[type='submit'], a[href='/users'])
                - Then: text content based (button:has-text('Save'))
                - Avoid: fragile class-only selectors or nth-child
                """,
                appUrl, appDescription, pageContent, maxSteps);

        try {
            String response = claudeApiService.analyze(prompt).block();
            return parseTestSteps(response);
        } catch (Exception e) {
            log.error("Failed to plan test steps: {}", e.getMessage());
            return getDefaultTestSteps(appUrl);
        }
    }

    /**
     * Ask AI to decide the next action based on current page state.
     * Used in adaptive testing mode where AI decides step-by-step.
     */
    public TestStep planNextStep(String appUrl, String pageContent,
                                  String consoleErrors, List<TestStep> completedSteps,
                                  String objective) {
        StringBuilder completedSummary = new StringBuilder();
        for (TestStep step : completedSteps) {
            completedSummary.append(String.format("  Step %d [%s]: %s - %s\n",
                    step.getStepNumber(), step.getStatus(), step.getAction(), step.getDescription()));
        }

        String prompt = String.format("""
                You are a QA engineer testing a web application step by step.

                ## Current Objective
                %s

                ## Current Page State
                %s

                ## Console Errors
                %s

                ## Completed Steps
                %s

                ## Your Task
                Based on the current page state and completed steps, decide the NEXT single action to take.
                If you have found a bug (element missing, button not responding, unexpected state), report it.
                If testing is complete, set action to "DONE".

                Respond ONLY in this JSON format:
                ```json
                {
                  "action": "CLICK",
                  "target": "button#submit",
                  "value": "",
                  "description": "Click submit button to save the form",
                  "bugFound": false,
                  "bugDescription": ""
                }
                ```

                Possible actions: NAVIGATE, CLICK, TYPE, SELECT, ASSERT, WAIT, DONE
                """,
                objective,
                pageContent,
                consoleErrors.isEmpty() ? "None" : consoleErrors,
                completedSummary.toString());

        try {
            String response = claudeApiService.analyze(prompt).block();
            return parseNextStep(response, completedSteps.size() + 1);
        } catch (Exception e) {
            log.error("Failed to plan next step: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<TestStep> parseTestSteps(String aiResponse) {
        try {
            String json = extractJson(aiResponse);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            List<Map<String, Object>> stepsData = (List<Map<String, Object>>) parsed.get("steps");

            List<TestStep> steps = new ArrayList<>();
            for (int i = 0; i < stepsData.size(); i++) {
                Map<String, Object> s = stepsData.get(i);
                steps.add(TestStep.builder()
                        .stepNumber(i + 1)
                        .action(TestStep.Action.valueOf(((String) s.get("action")).toUpperCase()))
                        .target((String) s.get("target"))
                        .value((String) s.getOrDefault("value", ""))
                        .description((String) s.get("description"))
                        .status(TestStep.StepStatus.PLANNED)
                        .build());
            }
            log.info("AI planned {} test steps", steps.size());
            return steps;

        } catch (Exception e) {
            log.error("Failed to parse test steps from AI response: {}", e.getMessage());
            return List.of();
        }
    }

    private TestStep parseNextStep(String aiResponse, int stepNumber) {
        try {
            String json = extractJson(aiResponse);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String action = ((String) parsed.get("action")).toUpperCase();
            if ("DONE".equals(action)) return null; // Testing complete

            return TestStep.builder()
                    .stepNumber(stepNumber)
                    .action(TestStep.Action.valueOf(action))
                    .target((String) parsed.get("target"))
                    .value((String) parsed.getOrDefault("value", ""))
                    .description((String) parsed.get("description"))
                    .status(TestStep.StepStatus.PLANNED)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse next step: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String text) {
        // Extract JSON from markdown code block or raw text
        int start = text.indexOf("```json");
        if (start >= 0) {
            start = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", start);
            return text.substring(start, end).trim();
        }
        start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * Default test steps when AI planning fails.
     * Basic smoke test: just navigate and check the page loads.
     */
    private List<TestStep> getDefaultTestSteps(String appUrl) {
        return List.of(
                TestStep.builder()
                        .stepNumber(1)
                        .action(TestStep.Action.NAVIGATE)
                        .target(appUrl)
                        .description("Navigate to application home page")
                        .status(TestStep.StepStatus.PLANNED)
                        .build(),
                TestStep.builder()
                        .stepNumber(2)
                        .action(TestStep.Action.ASSERT)
                        .target("body")
                        .description("Verify page body is present")
                        .status(TestStep.StepStatus.PLANNED)
                        .build()
        );
    }
}
