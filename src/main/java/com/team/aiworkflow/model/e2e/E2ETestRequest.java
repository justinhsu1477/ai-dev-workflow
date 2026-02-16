package com.team.aiworkflow.model.e2e;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 觸發 AI E2E 測試的請求物件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class E2ETestRequest {

    private String appUrl;           // 要測試的已部署應用程式 URL
    private String appDescription;   // 應用程式功能描述（幫助 AI 規劃測試）
    private String buildNumber;      // 選填：觸發此測試的 Build 編號
    private String branch;           // 選填：分支名稱
    private int maxSteps;            // 最大測試步驟數（預設 30）
    private int timeoutSeconds;      // 整個測試的逾時時間，秒（預設 300）
    private String triggeredBy;      // 觸發方式："manual"、"deployment-webhook" 等
    private String pushId;           // 選填：Azure DevOps push ID（績效追蹤用）
}
