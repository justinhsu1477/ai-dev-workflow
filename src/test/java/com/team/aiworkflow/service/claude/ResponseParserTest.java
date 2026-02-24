package com.team.aiworkflow.service.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResponseParser AI 回應解析測試")
class ResponseParserTest {

    private ResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new ResponseParser(new ObjectMapper());
    }

    @Nested
    @DisplayName("parseFailureAnalysis — 解析 CI 失敗分析")
    class ParseFailureAnalysisTests {

        @Test
        @DisplayName("應正確解析標準 JSON 回應")
        void shouldParseValidJsonResponse() {
            String response = """
                    ```json
                    {
                      "rootCause": "NullPointerException in UserService",
                      "severity": "HIGH",
                      "affectedFiles": ["UserService.java", "UserController.java"],
                      "suggestedFix": "加入 null check",
                      "summary": "User lookup 時物件為 null"
                    }
                    ```
                    """;

            AnalysisResult result = parser.parseFailureAnalysis(response, "100", "Build-100", "main", "abc123");

            assertThat(result.getRootCause()).isEqualTo("NullPointerException in UserService");
            assertThat(result.getSeverity()).isEqualTo(AnalysisResult.Severity.HIGH);
            assertThat(result.getAffectedFiles()).containsExactly("UserService.java", "UserController.java");
            assertThat(result.getSuggestedFix()).isEqualTo("加入 null check");
            assertThat(result.getSummary()).isEqualTo("User lookup 時物件為 null");
            assertThat(result.getBuildId()).isEqualTo("100");
            assertThat(result.getBranch()).isEqualTo("main");
        }

        @Test
        @DisplayName("severity 不分大小寫")
        void shouldParseSeverityCaseInsensitive() {
            String response = """
                    {"rootCause": "bug", "severity": "critical", "suggestedFix": "fix", "summary": "s"}
                    """;

            AnalysisResult result = parser.parseFailureAnalysis(response, "1", "B1", "main", "c1");

            assertThat(result.getSeverity()).isEqualTo(AnalysisResult.Severity.CRITICAL);
        }

        @Test
        @DisplayName("無效 severity 應預設為 MEDIUM")
        void shouldDefaultToMediumForInvalidSeverity() {
            String response = """
                    {"rootCause": "bug", "severity": "UNKNOWN", "suggestedFix": "fix", "summary": "s"}
                    """;

            AnalysisResult result = parser.parseFailureAnalysis(response, "1", "B1", "main", "c1");

            assertThat(result.getSeverity()).isEqualTo(AnalysisResult.Severity.MEDIUM);
        }

        @Test
        @DisplayName("無法解析 JSON 時應降級為 raw text")
        void shouldFallbackToRawTextWhenParsingFails() {
            String response = "這是純文字回應，沒有 JSON";

            AnalysisResult result = parser.parseFailureAnalysis(response, "1", "B1", "main", "c1");

            assertThat(result.getRootCause()).isEqualTo(response);
            assertThat(result.getSeverity()).isEqualTo(AnalysisResult.Severity.MEDIUM);
            assertThat(result.getSummary()).contains("raw text");
        }

        @Test
        @DisplayName("severity 為 null 時應預設為 MEDIUM")
        void shouldDefaultToMediumWhenSeverityIsNull() {
            String response = """
                    {"rootCause": "bug", "suggestedFix": "fix", "summary": "s"}
                    """;

            AnalysisResult result = parser.parseFailureAnalysis(response, "1", "B1", "main", "c1");

            assertThat(result.getSeverity()).isEqualTo(AnalysisResult.Severity.MEDIUM);
        }
    }

    @Nested
    @DisplayName("extractJson — JSON 擷取")
    class ExtractJsonTests {

        @Test
        @DisplayName("應從 markdown code block 中提取 JSON")
        void shouldExtractFromCodeBlock() {
            String input = """
                    前面的文字
                    ```json
                    {"key": "value"}
                    ```
                    後面的文字
                    """;

            String json = parser.extractJson(input);

            assertThat(json).isEqualTo("{\"key\": \"value\"}");
        }

        @Test
        @DisplayName("應從混合內容中找到 JSON 物件")
        void shouldExtractRawJson() {
            String input = "分析結果如下：{\"key\": \"value\"} 完成";

            String json = parser.extractJson(input);

            assertThat(json).isEqualTo("{\"key\": \"value\"}");
        }

        @Test
        @DisplayName("沒有 JSON 時應拋出異常")
        void shouldThrowWhenNoJsonFound() {
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> parser.extractJson("完全沒有 JSON"));
        }
    }
}
