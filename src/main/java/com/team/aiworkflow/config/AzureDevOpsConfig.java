package com.team.aiworkflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;

@Configuration
@ConfigurationProperties(prefix = "azure-devops")
@Getter
@Setter
public class AzureDevOpsConfig {

    private String organization;
    private String project;
    private String pat;
    private String baseUrl = "https://dev.azure.com";

    @Bean(name = "azureDevOpsWebClient")
    public WebClient azureDevOpsWebClient() {
        String encodedPat = Base64.getEncoder().encodeToString((":" + pat).getBytes());

        return WebClient.builder()
                .baseUrl(baseUrl + "/" + organization + "/" + project)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedPat)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /**
     * Returns the base API URL for Azure DevOps REST API calls.
     * Format: https://dev.azure.com/{organization}/{project}/_apis
     */
    public String getApiBaseUrl() {
        return baseUrl + "/" + organization + "/" + project + "/_apis";
    }
}
