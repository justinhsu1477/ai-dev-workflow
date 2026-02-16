package com.team.aiworkflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 工程師績效追蹤設定。
 */
@Configuration
@ConfigurationProperties(prefix = "workflow.engineer-stats")
@Getter
@Setter
public class EngineerStatsConfig {

    /** 是否啟用工程師績效追蹤 */
    private boolean enabled = false;

    /** 連續測試失敗幾次後通知主管 */
    private int consecutiveFailureThreshold = 3;

    /** 主管專用的 Teams Webhook URL（與一般通知分開） */
    private String managerTeamsWebhookUrl;

    /** 每週摘要報告的 cron 排程 */
    private String cronWeeklySummary = "0 0 9 * * MON";

    /** 每月摘要報告的 cron 排程 */
    private String cronMonthlySummary = "0 0 9 1 * *";
}
