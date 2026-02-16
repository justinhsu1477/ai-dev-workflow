package com.team.aiworkflow.model.e2e;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI E2E 測試的完整執行結果。
 * 包含所有測試步驟、發現的 bug 和統計資訊。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class E2ETestResult {

    private String testRunId;          // 測試執行 ID
    private String appUrl;             // 測試的應用程式 URL
    private String appDescription;     // 應用程式描述
    private LocalDateTime startedAt;   // 開始時間
    private LocalDateTime completedAt; // 完成時間
    private long totalDurationMs;      // 總耗時（毫秒）

    private List<TestStep> steps;          // 所有測試步驟
    private List<BugFound> bugsFound;      // 發現的 bug 清單

    private int totalSteps;       // 總步驟數
    private int passedSteps;      // 通過步驟數
    private int failedSteps;      // 失敗步驟數
    private TestRunStatus status; // 測試執行狀態
    private String summary;       // 測試摘要

    // 中繼資料
    private String triggeredBy;   // 觸發方式："deployment-webhook"、"push-webhook"、"manual"
    private String buildNumber;   // Build 編號
    private String branch;        // 分支名稱

    /**
     * 測試執行狀態列舉。
     */
    public enum TestRunStatus {
        RUNNING,    // 正在執行中
        PASSED,     // 所有步驟通過，未發現 bug
        FAILED,     // 發現 bug
        ERROR,      // 測試本身執行失敗（基礎設施問題）
        TIMEOUT     // 測試執行逾時
    }

    /**
     * 代表 E2E 測試中發現的單一 bug。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BugFound {
        private String title;            // Bug 標題
        private String description;      // Bug 描述
        private String severity;         // 嚴重程度：LOW, MEDIUM, HIGH, CRITICAL
        private int stepNumber;          // 發現此 bug 的步驟編號
        private String pageUrl;          // 發現 bug 時的頁面 URL
        private String consoleErrors;    // Console 錯誤訊息
        private String expectedBehavior; // 預期行為
        private String actualBehavior;   // 實際行為
        private int workItemId;          // 建立的 Azure DevOps Work Item ID
        private String attachmentUrl;    // Azure DevOps 截圖附件 URL

        @JsonIgnore
        private byte[] screenshotData;   // 截圖二進位資料（不序列化，上傳後丟棄）
    }
}
