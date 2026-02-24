package com.team.aiworkflow.service.autofix;

import com.team.aiworkflow.config.AutoFixConfig;
import com.team.aiworkflow.config.ModuleMappingConfig;
import com.team.aiworkflow.config.ModuleMappingConfig.ModuleDefinition;
import com.team.aiworkflow.config.ModuleMappingConfig.TestFlowDefinition;
import com.team.aiworkflow.model.e2e.E2ETestResult.BugFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SourceCodeResolver 原始碼定位測試")
class SourceCodeResolverTest {

    private SourceCodeResolver resolver;
    private AutoFixConfig autoFixConfig;
    private ModuleMappingConfig.ModuleMapping moduleMapping;

    @TempDir
    Path tempRepo;

    @BeforeEach
    void setUp() {
        autoFixConfig = new AutoFixConfig();
        autoFixConfig.setTargetRepoPath(tempRepo.toString());
        autoFixConfig.setSourceBasePath("src/main/java/com/soetek/ods");
        autoFixConfig.setMaxFilesToRead(10);

        moduleMapping = new ModuleMappingConfig.ModuleMapping();
        moduleMapping.setModules(List.of());

        resolver = new SourceCodeResolver(autoFixConfig, moduleMapping);
    }

    @Nested
    @DisplayName("extractComponentNames — 從描述中提取元件名稱")
    class ExtractComponentNamesTests {

        @Test
        @DisplayName("應提取 PascalCase 元件名稱")
        void shouldExtractPascalCaseNames() {
            List<String> names = resolver.extractComponentNames(
                    "OrderGridComponent 載入資料失敗，D2SearchView 也有問題");

            assertThat(names).containsExactly("OrderGridComponent", "D2SearchView");
        }

        @Test
        @DisplayName("應識別常見後綴：View, Service, Component, Grid, Dialog")
        void shouldRecognizeCommonSuffixes() {
            List<String> names = resolver.extractComponentNames(
                    "看起來 UserService 呼叫了 LoginDialog 之後，MainLayout 壞了，" +
                    "而且 SearchForm 也有問題");

            assertThat(names).containsExactly("UserService", "LoginDialog", "MainLayout", "SearchForm");
        }

        @Test
        @DisplayName("不應重複提取同一元件")
        void shouldNotExtractDuplicates() {
            List<String> names = resolver.extractComponentNames(
                    "OrderGridComponent 失敗了，OrderGridComponent 又失敗了");

            assertThat(names).containsExactly("OrderGridComponent");
        }

        @Test
        @DisplayName("null 輸入應回傳空 list")
        void shouldReturnEmptyForNull() {
            List<String> names = resolver.extractComponentNames(null);
            assertThat(names).isEmpty();
        }

        @Test
        @DisplayName("沒有元件名稱應回傳空 list")
        void shouldReturnEmptyWhenNoComponentNames() {
            List<String> names = resolver.extractComponentNames("按鈕點不動，頁面沒有反應");
            assertThat(names).isEmpty();
        }
    }

    @Nested
    @DisplayName("findModuleByRoute — 根據 URL 路由尋找模組")
    class FindModuleByRouteTests {

        @BeforeEach
        void setUpModules() {
            TestFlowDefinition orderFlow = new TestFlowDefinition();
            orderFlow.setRoute("/order/d2");

            ModuleDefinition orderModule = new ModuleDefinition();
            orderModule.setName("訂貨作業");
            orderModule.setTestFlows(List.of(orderFlow));
            orderModule.setFilePatterns(List.of("**/views/order/**"));

            TestFlowDefinition loginFlow = new TestFlowDefinition();
            loginFlow.setRoute("/login");

            ModuleDefinition authModule = new ModuleDefinition();
            authModule.setName("登入");
            authModule.setTestFlows(List.of(loginFlow));
            authModule.setFilePatterns(List.of("**/security/**"));

            moduleMapping.setModules(List.of(orderModule, authModule));
        }

        @Test
        @DisplayName("完整 URL 應匹配到正確模組")
        void shouldMatchModuleFromFullUrl() {
            ModuleDefinition result = resolver.findModuleByRoute("http://localhost:8080/order/d2");
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("訂貨作業");
        }

        @Test
        @DisplayName("只有路徑部分也應匹配")
        void shouldMatchFromPathOnly() {
            ModuleDefinition result = resolver.findModuleByRoute("/login");
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("登入");
        }

        @Test
        @DisplayName("不匹配的路由應回傳 null")
        void shouldReturnNullForUnmatchedRoute() {
            ModuleDefinition result = resolver.findModuleByRoute("http://localhost:8080/unknown/page");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null URL 應回傳 null")
        void shouldReturnNullForNullUrl() {
            ModuleDefinition result = resolver.findModuleByRoute(null);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("resolveSourceFiles — 完整原始碼搜尋")
    class ResolveSourceFilesTests {

        @Test
        @DisplayName("應根據元件名稱找到對應的 .java 檔")
        void shouldFindFilesByComponentName() throws IOException {
            Path sourceDir = tempRepo.resolve("src/main/java/com/soetek/ods/views/order");
            Files.createDirectories(sourceDir);
            Files.writeString(sourceDir.resolve("OrderGridComponent.java"),
                    "public class OrderGridComponent {}");

            BugFound bug = new BugFound();
            bug.setActualBehavior("OrderGridComponent 載入失敗");
            bug.setPageUrl(null);

            Map<String, String> files = resolver.resolveSourceFiles(bug);

            assertThat(files).isNotEmpty();
            assertThat(files.keySet()).anyMatch(key -> key.contains("OrderGridComponent.java"));
        }

        @Test
        @DisplayName("原始碼目錄不存在時應回傳空 map")
        void shouldReturnEmptyWhenSourceDirNotExists() {
            autoFixConfig.setSourceBasePath("nonexistent/path");

            BugFound bug = new BugFound();
            bug.setActualBehavior("SomeComponent 失敗");

            Map<String, String> files = resolver.resolveSourceFiles(bug);

            assertThat(files).isEmpty();
        }

        @Test
        @DisplayName("不應超過 maxFilesToRead 限制")
        void shouldRespectMaxFilesLimit() throws IOException {
            autoFixConfig.setMaxFilesToRead(2);
            Path sourceDir = tempRepo.resolve("src/main/java/com/soetek/ods");
            Files.createDirectories(sourceDir);

            for (int i = 0; i < 5; i++) {
                Files.writeString(sourceDir.resolve("TestComponent" + i + ".java"),
                        "public class TestComponent" + i + " {}");
            }

            BugFound bug = new BugFound();
            bug.setActualBehavior("TestComponent0 和 TestComponent1 和 TestComponent2 和 TestComponent3 和 TestComponent4");

            Map<String, String> files = resolver.resolveSourceFiles(bug);

            assertThat(files.size()).isLessThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("buildCodeContext — 組裝 prompt 用 code context")
    class BuildCodeContextTests {

        @Test
        @DisplayName("應將多個檔案組裝成格式化字串")
        void shouldFormatMultipleFiles() {
            Map<String, String> files = Map.of(
                    "A.java", "class A {}",
                    "B.java", "class B {}");

            String context = resolver.buildCodeContext(files);

            assertThat(context).contains("=== A.java ===");
            assertThat(context).contains("class A {}");
            assertThat(context).contains("=== B.java ===");
            assertThat(context).contains("class B {}");
        }

        @Test
        @DisplayName("空 map 應回傳空字串")
        void shouldReturnEmptyStringForEmptyMap() {
            String context = resolver.buildCodeContext(Map.of());
            assertThat(context).isEmpty();
        }
    }
}
