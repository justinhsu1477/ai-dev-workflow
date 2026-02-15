package com.team.aiworkflow.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Value("${rate-limit.claude-api.requests-per-minute:10}")
    private int requestsPerMinute;

    @Value("${rate-limit.claude-api.requests-per-hour:100}")
    private int requestsPerHour;

    @Bean(name = "claudeApiRateLimiter")
    public Bucket claudeApiRateLimiter() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillGreedy(requestsPerMinute, Duration.ofMinutes(1)))
                .addLimit(limit -> limit.capacity(requestsPerHour).refillGreedy(requestsPerHour, Duration.ofHours(1)))
                .build();
    }
}
