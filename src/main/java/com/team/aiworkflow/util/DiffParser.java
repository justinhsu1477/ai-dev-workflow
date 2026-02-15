package com.team.aiworkflow.util;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for parsing and formatting git diffs.
 */
@Component
public class DiffParser {

    private static final Pattern FILE_HEADER_PATTERN = Pattern.compile("^diff --git a/(.*) b/(.*)$");
    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile("^@@.*@@(.*)$");

    /**
     * Extract the list of changed file paths from a unified diff.
     */
    public List<String> extractChangedFiles(String diff) {
        if (diff == null || diff.isBlank()) return List.of();

        List<String> files = new ArrayList<>();
        Matcher matcher = FILE_HEADER_PATTERN.matcher(diff);
        while (matcher.find()) {
            files.add(matcher.group(2));
        }
        return files;
    }

    /**
     * Truncate diff to only include the most relevant portions.
     * Keeps file headers and changed lines, removes excessive context.
     */
    public String truncateDiff(String diff, int maxLines) {
        if (diff == null) return "";

        String[] lines = diff.split("\n");
        if (lines.length <= maxLines) return diff;

        StringBuilder sb = new StringBuilder();
        int lineCount = 0;

        for (String line : lines) {
            if (lineCount >= maxLines) {
                sb.append("\n... (diff truncated, ").append(lines.length - maxLines)
                        .append(" more lines) ...\n");
                break;
            }

            // Always include file headers and hunk headers
            if (line.startsWith("diff --git") || line.startsWith("---") ||
                    line.startsWith("+++") || line.startsWith("@@") ||
                    line.startsWith("+") || line.startsWith("-")) {
                sb.append(line).append("\n");
                lineCount++;
            }
        }

        return sb.toString();
    }
}
