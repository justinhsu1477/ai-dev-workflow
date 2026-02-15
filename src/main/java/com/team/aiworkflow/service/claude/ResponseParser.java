package com.team.aiworkflow.service.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.model.AnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AI response text into structured objects.
 * Handles JSON extraction from markdown code blocks.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ResponseParser {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    /**
     * Parse failure analysis response into AnalysisResult.
     */
    public AnalysisResult parseFailureAnalysis(String aiResponse, String buildId, String buildNumber, String branch, String commitId) {
        try {
            String jsonStr = extractJson(aiResponse);
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, new TypeReference<>() {});

            return AnalysisResult.builder()
                    .buildId(buildId)
                    .buildNumber(buildNumber)
                    .branch(branch)
                    .commitId(commitId)
                    .rootCause((String) parsed.get("rootCause"))
                    .suggestedFix((String) parsed.get("suggestedFix"))
                    .severity(parseSeverity((String) parsed.get("severity")))
                    .affectedFiles(parseStringList(parsed.get("affectedFiles")))
                    .summary((String) parsed.get("summary"))
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse AI response as JSON, using raw text. Error: {}", e.getMessage());
            return AnalysisResult.builder()
                    .buildId(buildId)
                    .buildNumber(buildNumber)
                    .branch(branch)
                    .commitId(commitId)
                    .rootCause(aiResponse)
                    .severity(AnalysisResult.Severity.MEDIUM)
                    .summary("AI analysis completed (raw text - JSON parsing failed)")
                    .build();
        }
    }

    /**
     * Extract JSON content from a response that may contain markdown code blocks.
     */
    String extractJson(String text) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // Try to find raw JSON object
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new RuntimeException("No JSON found in AI response");
    }

    private AnalysisResult.Severity parseSeverity(String severity) {
        if (severity == null) return AnalysisResult.Severity.MEDIUM;
        try {
            return AnalysisResult.Severity.valueOf(severity.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return AnalysisResult.Severity.MEDIUM;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Object obj) {
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return List.of();
    }
}
