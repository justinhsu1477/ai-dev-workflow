package com.team.aiworkflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 自動修復功能的設定。
 * E2E 測試發現 bug 後，AI 自動產生修復方案並套用到目標 repo。
 */
@Configuration
@ConfigurationProperties(prefix = "workflow.auto-fix")
@Getter
@Setter
public class AutoFixConfig {

    /** 是否啟用自動修復 */
    private boolean enabled = false;

    /** 目標 app 的本機 clone 路徑 */
    private String targetRepoPath;

    /** Azure DevOps repo ID（建立 PR 用） */
    private String targetRepoId;

    /** 目標 app 的主要開發分支 */
    private String baseBranch = "ai-dev-workflow";

    /** fix 分支的前綴 */
    private String branchPrefix = "ai-fix/";

    /** 最多讀取幾個原始碼檔案（控制 token 用量） */
    private int maxFilesToRead = 10;

    /** 目標 app 的 Java 源碼根目錄（相對於 repo root） */
    private String sourceBasePath = "src/main/java/com/soetek/ods";
}
