package com.team.aiworkflow.service.claude;

import com.team.aiworkflow.model.e2e.E2ETestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds prompts from external template files.
 * Templates are stored in src/main/resources/prompts/ and can be
 * modified without recompiling the application.
 */
@Component
@Slf4j
public class PromptBuilder {

    private final Map<String, String> templates = new HashMap<>();

    @PostConstruct
    public void loadTemplates() {
        loadTemplate("failure-analysis", "prompts/failure-analysis.txt");
        loadTemplate("test-generation", "prompts/test-generation.txt");
        loadTemplate("bug-fix", "prompts/bug-fix.txt");
        log.info("Loaded {} prompt templates", templates.size());
    }

    private void loadTemplate(String name, String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            templates.put(name, content);
        } catch (IOException e) {
            log.warn("Could not load prompt template '{}': {}", name, e.getMessage());
        }
    }

    /**
     * Build prompt for test failure analysis (Module 1).
     *
     * @param testLog    The truncated test failure log
     * @param codeDiff   The recent git diff (code changes)
     * @param buildInfo  Build metadata (branch, build number, etc.)
     * @return Complete prompt string for Claude API
     */
    public String buildFailureAnalysisPrompt(String testLog, String codeDiff, String buildInfo) {
        String template = templates.getOrDefault("failure-analysis", getDefaultFailureAnalysisTemplate());
        return template
                .replace("{{TEST_LOG}}", testLog != null ? testLog : "No log available")
                .replace("{{CODE_DIFF}}", codeDiff != null ? codeDiff : "No diff available")
                .replace("{{BUILD_INFO}}", buildInfo != null ? buildInfo : "No build info");
    }

    /**
     * Build prompt for test generation (Module 3).
     */
    public String buildTestGenerationPrompt(String codeDiff, String existingTests) {
        String template = templates.getOrDefault("test-generation", getDefaultTestGenTemplate());
        return template
                .replace("{{CODE_DIFF}}", codeDiff != null ? codeDiff : "")
                .replace("{{EXISTING_TESTS}}", existingTests != null ? existingTests : "No existing tests");
    }

    /**
     * Build prompt for bug fix (Module 2).
     */
    public String buildBugFixPrompt(String bugDescription, String codeContext, String stackTrace) {
        String template = templates.getOrDefault("bug-fix", getDefaultBugFixTemplate());
        return template
                .replace("{{BUG_DESCRIPTION}}", bugDescription != null ? bugDescription : "")
                .replace("{{CODE_CONTEXT}}", codeContext != null ? codeContext : "")
                .replace("{{STACK_TRACE}}", stackTrace != null ? stackTrace : "No stack trace");
    }

    /**
     * 從 E2E 測試發現的 Bug 建立修復 prompt。
     * 提取 bug 的各項資訊，組裝成 bug description，再呼叫 buildBugFixPrompt。
     */
    public String buildE2EBugFixPrompt(E2ETestResult.BugFound bug, String codeContext) {
        StringBuilder bugDesc = new StringBuilder();
        bugDesc.append("## Page URL\n").append(bug.getPageUrl() != null ? bug.getPageUrl() : "N/A").append("\n\n");
        bugDesc.append("## Expected Behavior\n").append(bug.getExpectedBehavior() != null ? bug.getExpectedBehavior() : "N/A").append("\n\n");
        bugDesc.append("## Actual Behavior\n").append(bug.getActualBehavior() != null ? bug.getActualBehavior() : "N/A").append("\n\n");
        bugDesc.append("## Bug Description\n").append(bug.getDescription() != null ? bug.getDescription() : "N/A").append("\n");

        String consoleErrors = bug.getConsoleErrors();
        return buildBugFixPrompt(bugDesc.toString(), codeContext, consoleErrors);
    }

    private String getDefaultFailureAnalysisTemplate() {
        return """
                You are a senior software engineer analyzing a CI/CD test failure.

                ## Build Information
                {{BUILD_INFO}}

                ## Test Failure Log
                ```
                {{TEST_LOG}}
                ```

                ## Recent Code Changes (Git Diff)
                ```diff
                {{CODE_DIFF}}
                ```

                ## Your Task
                Analyze the test failure and provide:
                1. **Root Cause**: What caused the test to fail? Be specific.
                2. **Severity**: Rate as LOW / MEDIUM / HIGH / CRITICAL
                3. **Affected Files**: List the files that are likely involved
                4. **Suggested Fix**: Provide a concrete fix suggestion with code if possible
                5. **Summary**: One-line summary of the issue

                Respond in the following JSON format:
                ```json
                {
                  "rootCause": "...",
                  "severity": "MEDIUM",
                  "affectedFiles": ["file1.java", "file2.java"],
                  "suggestedFix": "...",
                  "summary": "..."
                }
                ```
                """;
    }

    private String getDefaultTestGenTemplate() {
        return """
                You are a senior software engineer. Generate unit tests for the following code changes.

                ## Code Changes
                ```diff
                {{CODE_DIFF}}
                ```

                ## Existing Tests
                {{EXISTING_TESTS}}

                ## Requirements
                - Use JUnit 5 and Mockito
                - Cover happy path, edge cases, and error scenarios
                - Follow existing test naming conventions
                - Do NOT duplicate existing test cases
                - Include assertions with meaningful messages

                Provide the complete test class code.
                """;
    }

    private String getDefaultBugFixTemplate() {
        return """
                You are a senior software engineer fixing a bug.

                ## Bug Description
                {{BUG_DESCRIPTION}}

                ## Stack Trace
                ```
                {{STACK_TRACE}}
                ```

                ## Related Code
                {{CODE_CONTEXT}}

                ## Requirements
                - Provide the minimal fix needed
                - Do not refactor unrelated code
                - Explain why this fix works
                - Consider backward compatibility

                Respond in JSON format:
                ```json
                {
                  "fixDescription": "...",
                  "changes": [
                    {
                      "filePath": "...",
                      "originalCode": "...",
                      "fixedCode": "..."
                    }
                  ],
                  "explanation": "...",
                  "testSuggestion": "..."
                }
                ```
                """;
    }
}
