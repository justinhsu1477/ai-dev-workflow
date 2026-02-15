package com.team.aiworkflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "claude")
@Getter
@Setter
public class ClaudeApiConfig {

    private String apiKey;
    private String model = "claude-sonnet-4-5-20250929";
    private String modelComplex = "claude-opus-4-6";
    private int maxTokens = 4096;
    private int timeoutSeconds = 60;
}
