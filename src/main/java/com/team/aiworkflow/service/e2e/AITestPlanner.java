package com.team.aiworkflow.service.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.model.e2e.TestStep;
import com.team.aiworkflow.service.claude.ClaudeApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 透過 Claude API 規劃 E2E 測試步驟。
 * AI 扮演 QA 工程師角色，根據應用程式描述和當前頁面狀態決定要測試什麼。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AITestPlanner {

    private final ClaudeApiService claudeApiService;
    private final ObjectMapper objectMapper;

    /**
     * 根據應用程式描述產生初始測試計畫。
     * AI 決定要測試哪些使用者流程（例如 CRUD 操作）。
     *
     * @param appUrl         應用程式 URL
     * @param appDescription 應用程式功能描述
     * @param pageContent    當前頁面內容（無障礙樹 + 互動元素）
     * @param maxSteps       最大測試步驟數
     * @return 規劃好的測試步驟清單
     */
    public List<TestStep> planTestSteps(String appUrl, String appDescription,
                                         String pageContent, int maxSteps) {
        String prompt = String.format("""
                You are a senior QA engineer planning E2E tests for a Vaadin web application.
                Your goal is to find REAL BUGS by actually performing user operations and verifying data integrity.

                ## Application Info
                - URL: %s
                - Description: %s

                ## Current Page State
                %s

                ## Your Task
                Plan test steps that SIMULATE A REAL USER performing the COMPLETE workflow described in the steps-hint.

                ## CRITICAL RULES (MUST FOLLOW)

                1. **Follow the steps-hint EXACTLY**: Each action verb in the hint (NAVIGATE, CLICK, TYPE, WAIT, ASSERT)
                   MUST become at least one step. Do NOT skip any step from the hint.

                2. **CLICK means CLICK, not ASSERT**: When the hint says "CLICK 儲存按鈕", you MUST generate a CLICK action.
                   Never replace a CLICK with an ASSERT. ASSERT only checks if an element exists — it does NOT click it.

                3. **Handle confirmation dialogs**: Many save/delete operations show a confirmation dialog (e.g., "確定要存檔嗎?").
                   If the hint mentions "確認對話框", you MUST add a CLICK step for the confirm button.
                   Even if the hint doesn't mention it, if the app might show a confirm dialog, add a CLICK step.
                   Typical confirm button selectors: vaadin-button:has-text('確定'), vaadin-button:has-text('確認')

                4. **Verify DATA after write operations**: After save/submit/update, don't just check the notification.
                   You MUST verify the data was actually persisted:
                   - ASSERT that the modified field value is still showing the new value (not reverted to old value)
                   - Example: If you TYPE "5" in a quantity field, after saving, ASSERT that field still shows "5"

                5. **Test flow pattern for write operations**:
                   TYPE new value → CLICK save → CLICK confirm dialog → ASSERT notification → ASSERT data persisted
                   Note: If the grid fields are already editable after querying, skip CLICK edit.

                6. **Step budget**: Follow the steps-hint EXACTLY — do NOT add extra steps beyond what the hint specifies.
                   Do NOT duplicate similar ASSERT steps. Each step must serve a unique purpose.

                ## JSON Format (respond ONLY with this)
                ```json
                {
                  "steps": [
                    {"action": "NAVIGATE", "target": "/order/d2", "description": "Navigate to order page"},
                    {"action": "CLICK", "target": "vaadin-button:has-text('查詢')", "description": "Click search button to load data"},
                    {"action": "TYPE", "target": "vaadin-text-field[data-col='0']", "value": "5", "description": "Enter quantity 5 in first editable field"},
                    {"action": "CLICK", "target": "vaadin-button:has-text('儲存')", "description": "Click save button"},
                    {"action": "CLICK", "target": "vaadin-button:has-text('確定')", "description": "Click confirm button in save dialog"},
                    {"action": "ASSERT", "target": "vaadin-notification", "description": "Verify save notification appeared"},
                    {"action": "ASSERT", "target": "vaadin-text-field[data-col='0']", "description": "Verify quantity field still shows the saved value (data persisted)"}
                  ]
                }
                ```

                ## Selector Rules (Vaadin)
                - Prefer text: vaadin-button:has-text('儲存'), :has-text('查詢')
                - Vaadin tags: vaadin-button, vaadin-text-field, vaadin-integer-field, vaadin-grid, vaadin-combo-box
                - Grid editable fields: [data-row][data-col]:not([readonly]), vaadin-text-field[data-col='0']
                - Confirm dialogs: vaadin-confirm-dialog vaadin-button, vaadin-dialog-overlay vaadin-button:has-text('確定')
                - Notifications: vaadin-notification, vaadin-notification-card
                - Avoid: fragile class-only or nth-child selectors
                """,
                appUrl, appDescription, pageContent, maxSteps);

        try {
            String response = claudeApiService.analyze(prompt).block();
            return parseTestSteps(response);
        } catch (Exception e) {
            log.error("規劃測試步驟失敗：{}", e.getMessage());
            return getDefaultTestSteps(appUrl);
        }
    }

    /**
     * 根據當前頁面狀態，請 AI 決定下一步操作。
     * 用於自適應測試模式，AI 逐步決定下一個動作。
     *
     * @param appUrl         應用程式 URL
     * @param pageContent    當前頁面內容
     * @param consoleErrors  console 錯誤訊息
     * @param completedSteps 已完成的步驟
     * @param objective      當前測試目標
     * @return 下一個測試步驟，若測試完成則回傳 null
     */
    public TestStep planNextStep(String appUrl, String pageContent,
                                  String consoleErrors, List<TestStep> completedSteps,
                                  String objective) {
        StringBuilder completedSummary = new StringBuilder();
        for (TestStep step : completedSteps) {
            completedSummary.append(String.format("  Step %d [%s]: %s - %s\n",
                    step.getStepNumber(), step.getStatus(), step.getAction(), step.getDescription()));
        }

        String prompt = String.format("""
                You are a QA engineer testing a web application step by step.

                ## Current Objective
                %s

                ## Current Page State
                %s

                ## Console Errors
                %s

                ## Completed Steps
                %s

                ## Your Task
                Based on the current page state and completed steps, decide the NEXT single action to take.
                If you have found a bug (element missing, button not responding, unexpected state), report it.
                If testing is complete, set action to "DONE".

                Respond ONLY in this JSON format:
                ```json
                {
                  "action": "CLICK",
                  "target": "button#submit",
                  "value": "",
                  "description": "Click submit button to save the form",
                  "bugFound": false,
                  "bugDescription": ""
                }
                ```

                Possible actions: NAVIGATE, CLICK, TYPE, SELECT, ASSERT, WAIT, DONE
                """,
                objective,
                pageContent,
                consoleErrors.isEmpty() ? "None" : consoleErrors,
                completedSummary.toString());

        try {
            String response = claudeApiService.analyze(prompt).block();
            return parseNextStep(response, completedSteps.size() + 1);
        } catch (Exception e) {
            log.error("規劃下一步失敗：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 AI 回傳的測試步驟 JSON。
     */
    @SuppressWarnings("unchecked")
    private List<TestStep> parseTestSteps(String aiResponse) {
        try {
            String json = extractJson(aiResponse);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            List<Map<String, Object>> stepsData = (List<Map<String, Object>>) parsed.get("steps");

            List<TestStep> steps = new ArrayList<>();
            for (int i = 0; i < stepsData.size(); i++) {
                Map<String, Object> s = stepsData.get(i);
                steps.add(TestStep.builder()
                        .stepNumber(i + 1)
                        .action(TestStep.Action.valueOf(((String) s.get("action")).toUpperCase()))
                        .target((String) s.get("target"))
                        .value((String) s.getOrDefault("value", ""))
                        .description((String) s.get("description"))
                        .status(TestStep.StepStatus.PLANNED)
                        .build());
            }
            log.info("AI 規劃了 {} 個測試步驟", steps.size());
            return steps;

        } catch (Exception e) {
            log.error("解析 AI 測試步驟失敗：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 AI 回傳的單一步驟 JSON。
     */
    private TestStep parseNextStep(String aiResponse, int stepNumber) {
        try {
            String json = extractJson(aiResponse);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String action = ((String) parsed.get("action")).toUpperCase();
            if ("DONE".equals(action)) return null; // 測試完成

            return TestStep.builder()
                    .stepNumber(stepNumber)
                    .action(TestStep.Action.valueOf(action))
                    .target((String) parsed.get("target"))
                    .value((String) parsed.getOrDefault("value", ""))
                    .description((String) parsed.get("description"))
                    .status(TestStep.StepStatus.PLANNED)
                    .build();

        } catch (Exception e) {
            log.error("解析下一步失敗：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 從 markdown code block 或原始文字中擷取 JSON。
     */
    private String extractJson(String text) {
        int start = text.indexOf("```json");
        if (start >= 0) {
            start = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", start);
            return text.substring(start, end).trim();
        }
        start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * AI 規劃失敗時的預設測試步驟。
     * 基本冒煙測試：導航到首頁並確認頁面載入。
     */
    private List<TestStep> getDefaultTestSteps(String appUrl) {
        return List.of(
                TestStep.builder()
                        .stepNumber(1)
                        .action(TestStep.Action.NAVIGATE)
                        .target(appUrl)
                        .description("導航到應用程式首頁")
                        .status(TestStep.StepStatus.PLANNED)
                        .build(),
                TestStep.builder()
                        .stepNumber(2)
                        .action(TestStep.Action.ASSERT)
                        .target("body")
                        .description("驗證頁面 body 存在")
                        .status(TestStep.StepStatus.PLANNED)
                        .build()
        );
    }
}
