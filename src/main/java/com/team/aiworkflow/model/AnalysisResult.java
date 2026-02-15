package com.team.aiworkflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    private String buildId;
    private String buildNumber;
    private String branch;
    private String commitId;

    // AI analysis output
    private String rootCause;
    private String suggestedFix;
    private Severity severity;
    private List<String> affectedFiles;
    private String summary;

    // Metadata
    private LocalDateTime analyzedAt;
    private String modelUsed;
    private int workItemId;

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
