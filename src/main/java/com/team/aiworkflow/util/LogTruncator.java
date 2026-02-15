package com.team.aiworkflow.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility for intelligently truncating build logs to fit within
 * Claude API token limits. Prioritizes error-related lines.
 */
@Component
public class LogTruncator {

    // Keywords that indicate important log lines
    private static final List<String> ERROR_KEYWORDS = List.of(
            "error", "fail", "exception", "assert", "stacktrace",
            "caused by", "at ", "null", "timeout", "refused",
            "cannot", "unable", "not found", "missing"
    );

    @Value("${workflow.failure-analysis.max-log-lines:500}")
    private int maxLogLines;

    /**
     * Truncate a log to a manageable size, prioritizing error lines.
     *
     * Strategy:
     * 1. If log is already within limits, return as-is
     * 2. Extract lines containing error keywords (with context)
     * 3. If still too long, keep first/last sections + error section
     *
     * @param log Raw build log text
     * @return Truncated log suitable for AI analysis
     */
    public String truncate(String log) {
        if (log == null || log.isBlank()) {
            return "No log content available";
        }

        String[] lines = log.split("\n");

        // If log is within limits, return as-is
        if (lines.length <= maxLogLines) {
            return log;
        }

        // Extract error-relevant lines with surrounding context (2 lines before/after)
        StringBuilder result = new StringBuilder();
        result.append("=== LOG TRUNCATED (").append(lines.length).append(" lines → ~")
                .append(maxLogLines).append(" lines) ===\n\n");

        // Keep first 20 lines (usually contain build setup info)
        result.append("--- Start of log ---\n");
        for (int i = 0; i < Math.min(20, lines.length); i++) {
            result.append(lines[i]).append("\n");
        }

        // Find and include error sections with context
        result.append("\n--- Error sections ---\n");
        int errorLinesAdded = 0;
        int contextRadius = 2;

        for (int i = 0; i < lines.length && errorLinesAdded < maxLogLines - 40; i++) {
            if (isErrorLine(lines[i])) {
                int start = Math.max(0, i - contextRadius);
                int end = Math.min(lines.length - 1, i + contextRadius);

                for (int j = start; j <= end; j++) {
                    result.append(lines[j]).append("\n");
                    errorLinesAdded++;
                }
                result.append("...\n");
                i = end; // Skip lines already added
            }
        }

        // Keep last 20 lines (usually contain summary/exit codes)
        result.append("\n--- End of log ---\n");
        for (int i = Math.max(0, lines.length - 20); i < lines.length; i++) {
            result.append(lines[i]).append("\n");
        }

        return result.toString();
    }

    private boolean isErrorLine(String line) {
        String lowerLine = line.toLowerCase();
        return ERROR_KEYWORDS.stream().anyMatch(lowerLine::contains);
    }
}
