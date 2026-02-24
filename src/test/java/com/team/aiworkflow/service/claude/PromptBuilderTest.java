package com.team.aiworkflow.service.claude;

import com.team.aiworkflow.model.e2e.E2ETestResult.BugFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromptBuilder Prompt 組裝測試")
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
        promptBuilder.loadTemplates();
    }

    @Nested
    @DisplayName("buildFailureAnalysisPrompt — CI 失敗分析 Prompt")
    class FailureAnalysisPromptTests {

        @Test
        @DisplayName("應正確替換所有模板變數")
        void shouldReplaceAllPlaceholders() {
            String prompt = promptBuilder.buildFailureAnalysisPrompt(
                    "NullPointerException at line 42",
                    "- public String getName() { return name; }",
                    "Build #100, branch: main");

            assertThat(prompt).contains("NullPointerException at line 42");
            assertThat(prompt).contains("public String getName()");
            assertThat(prompt).contains("Build #100");
            assertThat(prompt).doesNotContain("{{TEST_LOG}}");
            assertThat(prompt).doesNotContain("{{CODE_DIFF}}");
            assertThat(prompt).doesNotContain("{{BUILD_INFO}}");
        }

        @Test
        @DisplayName("null 參數應用預設文字替代")
        void shouldHandleNullParameters() {
            String prompt = promptBuilder.buildFailureAnalysisPrompt(null, null, null);

            assertThat(prompt).contains("No log available");
            assertThat(prompt).contains("No diff available");
            assertThat(prompt).contains("No build info");
        }
    }

    @Nested
    @DisplayName("buildBugFixPrompt — Bug 修復 Prompt")
    class BugFixPromptTests {

        @Test
        @DisplayName("應正確組裝 bug fix prompt")
        void shouldBuildBugFixPrompt() {
            String prompt = promptBuilder.buildBugFixPrompt(
                    "按鈕無反應", "class Button {}", "java.lang.NPE");

            assertThat(prompt).contains("按鈕無反應");
            assertThat(prompt).contains("class Button {}");
            assertThat(prompt).contains("java.lang.NPE");
        }

        @Test
        @DisplayName("應包含 JSON 格式要求")
        void shouldContainJsonFormatRequirement() {
            String prompt = promptBuilder.buildBugFixPrompt("bug", "code", "trace");

            assertThat(prompt).contains("fixDescription");
            assertThat(prompt).contains("changes");
            assertThat(prompt).contains("originalCode");
            assertThat(prompt).contains("fixedCode");
        }
    }

    @Nested
    @DisplayName("buildE2EBugFixPrompt — E2E Bug 修復 Prompt")
    class E2EBugFixPromptTests {

        @Test
        @DisplayName("應從 BugFound 物件組裝完整 prompt")
        void shouldBuildFromBugFoundObject() {
            BugFound bug = new BugFound();
            bug.setPageUrl("http://localhost:8080/order/d2");
            bug.setExpectedBehavior("應顯示訂單列表");
            bug.setActualBehavior("頁面空白");
            bug.setDescription("訂單載入失敗");
            bug.setConsoleErrors("TypeError: Cannot read properties of null");

            String prompt = promptBuilder.buildE2EBugFixPrompt(bug, "class OrderGrid {}");

            assertThat(prompt).contains("http://localhost:8080/order/d2");
            assertThat(prompt).contains("應顯示訂單列表");
            assertThat(prompt).contains("頁面空白");
            assertThat(prompt).contains("訂單載入失敗");
            assertThat(prompt).contains("TypeError");
            assertThat(prompt).contains("class OrderGrid {}");
        }

        @Test
        @DisplayName("BugFound 欄位為 null 時應用 N/A 替代")
        void shouldHandleNullFieldsInBugFound() {
            BugFound bug = new BugFound();

            String prompt = promptBuilder.buildE2EBugFixPrompt(bug, "code");

            assertThat(prompt).contains("N/A");
            assertThat(prompt).doesNotContain("null");
        }
    }

    @Nested
    @DisplayName("buildTestGenerationPrompt — 測試產生 Prompt")
    class TestGenerationPromptTests {

        @Test
        @DisplayName("應包含 JUnit 5 和 Mockito 要求")
        void shouldIncludeTestFrameworkRequirements() {
            String prompt = promptBuilder.buildTestGenerationPrompt("diff", "tests");

            assertThat(prompt).contains("JUnit 5");
            assertThat(prompt).contains("Mockito");
        }
    }
}
