package com.team.aiworkflow.model.e2e;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代表由 AI Agent 規劃或執行的單一 E2E 測試步驟。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestStep {

    private int stepNumber;          // 步驟編號
    private Action action;           // 操作類型
    private String target;           // CSS 選擇器、URL 或輸入值
    private String value;            // TYPE 操作時要輸入的文字
    private String description;      // 步驟的人類可讀描述
    private StepStatus status;       // 步驟狀態
    private String errorMessage;     // 失敗時的錯誤訊息
    private long durationMs;         // 執行耗時（毫秒）

    @JsonIgnore
    private byte[] screenshotData;   // 截圖二進位資料（不序列化到 JSON 回應）

    /**
     * 測試步驟的操作類型。
     */
    public enum Action {
        NAVIGATE,   // 導航到 URL
        CLICK,      // 點擊元素
        TYPE,       // 在輸入框中填入文字
        SELECT,     // 從下拉選單選擇選項
        ASSERT,     // 驗證頁面上的某個條件
        WAIT,       // 等待元素出現
        SCREENSHOT  // 截圖
    }

    /**
     * 測試步驟的執行狀態。
     */
    public enum StepStatus {
        PLANNED,    // 已規劃，尚未執行
        RUNNING,    // 正在執行中
        PASSED,     // 執行成功
        FAILED,     // 執行失敗（可能發現 bug）
        SKIPPED     // 已跳過
    }
}
