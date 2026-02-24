package com.team.aiworkflow.service.autofix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.config.AutoFixConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FixApplicator AI 修復方案解析與套用測試")
class FixApplicatorTest {

    private FixApplicator fixApplicator;
    private AutoFixConfig config;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempRepo;

    @BeforeEach
    void setUp() {
        config = new AutoFixConfig();
        config.setTargetRepoPath(tempRepo.toString());
        objectMapper = new ObjectMapper();
        fixApplicator = new FixApplicator(config, objectMapper);
    }

    @Nested
    @DisplayName("parseFixResponse — 解析 AI JSON 回應")
    class ParseFixResponseTests {

        @Test
        @DisplayName("應正確解析標準 JSON 回應")
        void shouldParseStandardJsonResponse() {
            String response = """
                    {
                      "fixDescription": "修復 null pointer",
                      "changes": [
                        {
                          "filePath": "src/main/java/Test.java",
                          "originalCode": "obj.method()",
                          "fixedCode": "if (obj != null) obj.method()"
                        }
                      ],
                      "explanation": "物件可能為 null",
                      "testSuggestion": "驗證 null 情境"
                    }
                    """;

            FixApplicator.FixResult result = fixApplicator.parseFixResponse(response);

            assertThat(result).isNotNull();
            assertThat(result.fixDescription()).isEqualTo("修復 null pointer");
            assertThat(result.changes()).hasSize(1);
            assertThat(result.changes().get(0).filePath()).isEqualTo("src/main/java/Test.java");
            assertThat(result.changes().get(0).originalCode()).isEqualTo("obj.method()");
            assertThat(result.changes().get(0).fixedCode()).isEqualTo("if (obj != null) obj.method()");
            assertThat(result.explanation()).isEqualTo("物件可能為 null");
            assertThat(result.testSuggestion()).isEqualTo("驗證 null 情境");
        }

        @Test
        @DisplayName("應正確從 markdown code block 中提取 JSON")
        void shouldParseJsonFromMarkdownCodeBlock() {
            String response = """
                    這是我的修復方案：

                    ```json
                    {
                      "fixDescription": "修正查詢條件",
                      "changes": [],
                      "explanation": "原始 SQL 缺少 WHERE 條件",
                      "testSuggestion": "測試有無條件的查詢"
                    }
                    ```

                    以上修復應該可以解決問題。
                    """;

            FixApplicator.FixResult result = fixApplicator.parseFixResponse(response);

            assertThat(result).isNotNull();
            assertThat(result.fixDescription()).isEqualTo("修正查詢條件");
        }

        @Test
        @DisplayName("AI 回傳無效 JSON 時應回傳 null")
        void shouldReturnNullForInvalidJson() {
            String response = "這不是有效的 JSON 回應，完全沒有大括號";

            FixApplicator.FixResult result = fixApplicator.parseFixResponse(response);

            // 沒有 {} 會被當作原始 text，解析會失敗
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("多個檔案變更應全部解析")
        void shouldParseMultipleChanges() {
            String response = """
                    {
                      "fixDescription": "修復多檔案問題",
                      "changes": [
                        {"filePath": "A.java", "originalCode": "a1", "fixedCode": "a2"},
                        {"filePath": "B.java", "originalCode": "b1", "fixedCode": "b2"},
                        {"filePath": "C.java", "originalCode": "c1", "fixedCode": "c2"}
                      ],
                      "explanation": "跨檔案依賴問題",
                      "testSuggestion": "測試三個檔案"
                    }
                    """;

            FixApplicator.FixResult result = fixApplicator.parseFixResponse(response);

            assertThat(result).isNotNull();
            assertThat(result.changes()).hasSize(3);
        }

        @Test
        @DisplayName("空白回應應回傳 null")
        void shouldReturnNullForEmptyResponse() {
            FixApplicator.FixResult result = fixApplicator.parseFixResponse("");
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("applyChanges — 套用修改到檔案系統")
    class ApplyChangesTests {

        @Test
        @DisplayName("精確比對替換應成功套用")
        void shouldApplyExactMatchChanges() throws IOException {
            // 建立測試檔案
            Path srcDir = tempRepo.resolve("src/main/java");
            Files.createDirectories(srcDir);
            Path testFile = srcDir.resolve("Test.java");
            Files.writeString(testFile, "public void method() {\n    return null;\n}");

            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復",
                    java.util.List.of(new FixApplicator.FixChange(
                            "src/main/java/Test.java",
                            "return null;",
                            "return new Object();")),
                    "解釋", "測試");

            boolean applied = fixApplicator.applyChanges(fixResult);

            assertThat(applied).isTrue();
            String content = Files.readString(testFile);
            assertThat(content).contains("return new Object();");
            assertThat(content).doesNotContain("return null;");
        }

        @Test
        @DisplayName("Fuzzy match 應在空白差異時仍能匹配套用")
        void shouldApplyFuzzyMatchWhenWhitespacesDiffer() throws IOException {
            Path srcDir = tempRepo.resolve("src/main/java");
            Files.createDirectories(srcDir);
            Path testFile = srcDir.resolve("Test.java");
            // 檔案中有額外空白
            Files.writeString(testFile, "  public void method() {\n      return null;  \n  }");

            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復",
                    java.util.List.of(new FixApplicator.FixChange(
                            "src/main/java/Test.java",
                            "public void method() {\n    return null;\n}",
                            "public void method() {\n    return new Object();\n}")),
                    "解釋", "測試");

            boolean applied = fixApplicator.applyChanges(fixResult);

            assertThat(applied).isTrue();
        }

        @Test
        @DisplayName("檔案不存在時應回傳 false")
        void shouldReturnFalseWhenFileNotFound() {
            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復",
                    java.util.List.of(new FixApplicator.FixChange(
                            "nonexistent/File.java", "old", "new")),
                    "解釋", "測試");

            boolean applied = fixApplicator.applyChanges(fixResult);

            assertThat(applied).isFalse();
        }

        @Test
        @DisplayName("空的 changes 清單應回傳 false")
        void shouldReturnFalseForEmptyChanges() {
            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復", java.util.Collections.emptyList(), "解釋", "測試");

            boolean applied = fixApplicator.applyChanges(fixResult);

            assertThat(applied).isFalse();
        }

        @Test
        @DisplayName("null fixResult 應回傳 false")
        void shouldReturnFalseForNullInput() {
            boolean applied = fixApplicator.applyChanges(null);
            assertThat(applied).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveFilePath — 檔案路徑解析")
    class ResolveFilePathTests {

        @Test
        @DisplayName("完整相對路徑應直接解析")
        void shouldResolveFullRelativePath() throws IOException {
            Path target = tempRepo.resolve("src/main/java/com/soetek/Test.java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "content");

            Path resolved = fixApplicator.resolveFilePath("src/main/java/com/soetek/Test.java");

            assertThat(resolved).isNotNull();
            assertThat(resolved).exists();
        }

        @Test
        @DisplayName("只有類別名稱時應搜尋 repo 找到檔案")
        void shouldSearchRepoForClassName() throws IOException {
            Path target = tempRepo.resolve("src/main/java/deep/nested/MyService.java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "content");

            Path resolved = fixApplicator.resolveFilePath("MyService.java");

            assertThat(resolved).isNotNull();
            assertThat(resolved.getFileName().toString()).isEqualTo("MyService.java");
        }

        @Test
        @DisplayName("null 路徑應回傳 null")
        void shouldReturnNullForNullPath() {
            Path resolved = fixApplicator.resolveFilePath(null);
            assertThat(resolved).isNull();
        }

        @Test
        @DisplayName("空白路徑應回傳 null")
        void shouldReturnNullForBlankPath() {
            Path resolved = fixApplicator.resolveFilePath("   ");
            assertThat(resolved).isNull();
        }
    }
}
