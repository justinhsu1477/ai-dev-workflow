package com.team.aiworkflow.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Internal DTO for creating Azure DevOps Work Items.
 */
@Data
@Builder
public class WorkItemCreateRequest {

    private String title;
    private String description;
    private String severity;
    private String areaPath;
    private String iterationPath;
    private String assignedTo;
    private String reproSteps;
    private String tags;
}
