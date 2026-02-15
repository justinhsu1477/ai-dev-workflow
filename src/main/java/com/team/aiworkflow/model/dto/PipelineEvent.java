package com.team.aiworkflow.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO for Azure DevOps Service Hook "Build completed" event payload.
 * Azure DevOps sends this JSON when a pipeline build completes.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineEvent {

    private String id;
    private String eventType;
    private Resource resource;
    private String createdDate;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resource {
        private int id;
        private String buildNumber;
        private String status;
        private String result;
        private String sourceBranch;
        private String sourceVersion;
        private String url;

        @JsonProperty("definition")
        private BuildDefinition definition;

        @JsonProperty("repository")
        private Repository repository;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BuildDefinition {
        private int id;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private String id;
        private String name;
        private String type;
    }
}
