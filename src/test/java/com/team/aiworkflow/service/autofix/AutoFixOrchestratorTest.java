package com.team.aiworkflow.service.autofix;

import com.team.aiworkflow.config.AutoFixConfig;
import com.team.aiworkflow.model.autofix.AutoFixResult;
import com.team.aiworkflow.model.autofix.AutoFixResult.AutoFixStatus;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.model.e2e.E2ETestResult.BugFound;
import com.team.aiworkflow.service.azuredevops.PullRequestService;
import com.team.aiworkflow.service.azuredevops.WorkItemService;
import com.team.aiworkflow.service.claude.ClaudeApiService;
import com.team.aiworkflow.service.claude.PromptBuilder;
import com.team.aiworkflow.service.stats.EngineerStatsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutoFixOrchestrator 自動修復流程測試")
class AutoFixOrchestratorTest {

    @Mock private AutoFixConfig config;
    @Mock private SourceCodeResolver sourceCodeResolver;
    @Mock private FixApplicator fixApplicator;
    @Mock private TargetRepoGitService gitService;
    @Mock private PromptBuilder promptBuilder;
    @Mock private ClaudeApiService claudeApiService;
    @Mock private PullRequestService pullRequestService;
    @Mock private WorkItemService workItemService;
    @Mock private EngineerStatsRecorder engineerStatsRecorder;

    @InjectMocks
    private AutoFixOrchestrator orchestrator;

    private BugFound sampleBug;
    private E2ETestRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleBug = new BugFound();
        sampleBug.setWorkItemId(123);
        sampleBug.setPageUrl("http://localhost:8080/order/d2");
        sampleBug.setDescription("按鈕點擊後無反應");
        sampleBug.setExpectedBehavior("應該顯示訂單明細");
        sampleBug.setActualBehavior("OrderGridComponent 沒有載入資料");

        sampleRequest = E2ETestRequest.builder()
                .appUrl("http://localhost:8080")
                .build();
    }

    @Nested
    @DisplayName("attemptFix — 階段 1 自動修復")
    class AttemptFixTests {

        @Test
        @DisplayName("當 auto-fix 未啟用時，應回傳 DISABLED 狀態")
        void shouldReturnDisabledWhenNotEnabled() {
            when(config.isEnabled()).thenReturn(false);

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.DISABLED);
            assertThat(result.getWorkItemId()).isEqualTo(123);
            verifyNoInteractions(sourceCodeResolver, claudeApiService);
        }

        @Test
        @DisplayName("找不到原始碼時，應回傳 NO_SOURCE_FILES 並加註解")
        void shouldReturnNoSourceFilesWhenNoneFound() {
            when(config.isEnabled()).thenReturn(true);
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Collections.emptyMap());
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.NO_SOURCE_FILES);
            verify(workItemService).addComment(eq(123), contains("找不到相關原始碼"));
        }

        @Test
        @DisplayName("AI 未回傳修復方案時，應回傳 AI_GENERATION_ERROR")
        void shouldReturnAiErrorWhenNullResponse() {
            when(config.isEnabled()).thenReturn(true);
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Map.of("Test.java", "code"));
            when(sourceCodeResolver.buildCodeContext(any())).thenReturn("code context");
            when(promptBuilder.buildE2EBugFixPrompt(any(), anyString())).thenReturn("prompt");
            when(claudeApiService.analyzeComplex(anyString())).thenReturn(Mono.empty());
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.AI_GENERATION_ERROR);
        }

        @Test
        @DisplayName("AI 回傳空白字串時，應回傳 AI_GENERATION_ERROR")
        void shouldReturnAiErrorWhenBlankResponse() {
            when(config.isEnabled()).thenReturn(true);
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Map.of("Test.java", "code"));
            when(sourceCodeResolver.buildCodeContext(any())).thenReturn("code context");
            when(promptBuilder.buildE2EBugFixPrompt(any(), anyString())).thenReturn("prompt");
            when(claudeApiService.analyzeComplex(anyString())).thenReturn(Mono.just("   "));
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.AI_GENERATION_ERROR);
        }

        @Test
        @DisplayName("無法解析修復方案 JSON 時，應回傳 AI_GENERATION_ERROR")
        void shouldReturnAiErrorWhenParsingFails() {
            when(config.isEnabled()).thenReturn(true);
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Map.of("Test.java", "code"));
            when(sourceCodeResolver.buildCodeContext(any())).thenReturn("code context");
            when(promptBuilder.buildE2EBugFixPrompt(any(), anyString())).thenReturn("prompt");
            when(claudeApiService.analyzeComplex(anyString())).thenReturn(Mono.just("invalid json"));
            when(fixApplicator.parseFixResponse("invalid json")).thenReturn(null);
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.AI_GENERATION_ERROR);
        }

        @Test
        @DisplayName("套用修改失敗時，應回傳 FIX_APPLY_ERROR 並放棄分支")
        void shouldReturnApplyErrorWhenChangesFailToApply() {
            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復描述", List.of(new FixApplicator.FixChange("Test.java", "old", "new")),
                    "解釋", "測試建議");

            when(config.isEnabled()).thenReturn(true);
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Map.of("Test.java", "code"));
            when(sourceCodeResolver.buildCodeContext(any())).thenReturn("code context");
            when(promptBuilder.buildE2EBugFixPrompt(any(), anyString())).thenReturn("prompt");
            when(claudeApiService.analyzeComplex(anyString())).thenReturn(Mono.just("ai response"));
            when(fixApplicator.parseFixResponse("ai response")).thenReturn(fixResult);
            when(gitService.createFixBranch(123)).thenReturn("ai-fix/123");
            when(fixApplicator.applyChanges(fixResult)).thenReturn(false);
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.FIX_APPLY_ERROR);
            // abandonBranch 被呼叫兩次：一次在 catch 區塊，一次在 finally 區塊
            verify(gitService, atLeast(1)).abandonBranch("ai-fix/123");
        }

        @Test
        @DisplayName("完整成功流程：Bug → 原始碼 → AI → 套用 → PR")
        void shouldCompleteFull9StepFlowSuccessfully() {
            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復 OrderGrid 載入問題", List.of(new FixApplicator.FixChange("Test.java", "old", "new")),
                    "原始查詢缺少必要參數", "驗證訂單列表載入");

            when(config.isEnabled()).thenReturn(true);
            when(config.getTargetRepoId()).thenReturn("repo-id-123");
            when(config.getBaseBranch()).thenReturn("ai-dev-workflow");
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Map.of("OrderGrid.java", "code"));
            when(sourceCodeResolver.buildCodeContext(any())).thenReturn("code context");
            when(promptBuilder.buildE2EBugFixPrompt(any(), anyString())).thenReturn("prompt");
            when(claudeApiService.analyzeComplex(anyString())).thenReturn(Mono.just("ai response"));
            when(fixApplicator.parseFixResponse("ai response")).thenReturn(fixResult);
            when(gitService.createFixBranch(123)).thenReturn("ai-fix/123");
            when(fixApplicator.applyChanges(fixResult)).thenReturn(true);
            when(pullRequestService.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                    .thenReturn(Mono.just(456));
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.FIX_APPLIED);
            assertThat(result.getWorkItemId()).isEqualTo(123);
            assertThat(result.getFixDescription()).isEqualTo("修復 OrderGrid 載入問題");
            assertThat(result.getBranchName()).isEqualTo("ai-fix/123");
            assertThat(result.getPullRequestId()).isEqualTo(456);
            assertThat(result.getDurationMs()).isGreaterThan(0);

            // 驗證完整流程呼叫順序
            verify(sourceCodeResolver).resolveSourceFiles(sampleBug);
            verify(claudeApiService).analyzeComplex(anyString());
            verify(gitService).createFixBranch(123);
            verify(fixApplicator).applyChanges(fixResult);
            verify(gitService).commitAndPush(eq("ai-fix/123"), contains("[AI Auto-Fix]"));
            verify(pullRequestService).createPullRequest(eq("repo-id-123"), eq("ai-fix/123"),
                    eq("ai-dev-workflow"), contains("[AI Auto-Fix]"), anyString(), eq(123));
            verify(workItemService).addComment(eq(123), contains("PR"));
        }

        @Test
        @DisplayName("PR 建立失敗不影響整體流程，仍回傳 FIX_APPLIED")
        void shouldStillSucceedEvenWhenPrCreationFails() {
            FixApplicator.FixResult fixResult = new FixApplicator.FixResult(
                    "修復描述", List.of(new FixApplicator.FixChange("Test.java", "old", "new")),
                    "解釋", "測試建議");

            when(config.isEnabled()).thenReturn(true);
            when(config.getTargetRepoId()).thenReturn("repo-id");
            when(config.getBaseBranch()).thenReturn("main");
            when(sourceCodeResolver.resolveSourceFiles(any())).thenReturn(Map.of("Test.java", "code"));
            when(sourceCodeResolver.buildCodeContext(any())).thenReturn("ctx");
            when(promptBuilder.buildE2EBugFixPrompt(any(), anyString())).thenReturn("p");
            when(claudeApiService.analyzeComplex(anyString())).thenReturn(Mono.just("resp"));
            when(fixApplicator.parseFixResponse("resp")).thenReturn(fixResult);
            when(gitService.createFixBranch(123)).thenReturn("ai-fix/123");
            when(fixApplicator.applyChanges(fixResult)).thenReturn(true);
            when(pullRequestService.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                    .thenReturn(Mono.error(new RuntimeException("Azure DevOps 連線失敗")));
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.FIX_APPLIED);
            assertThat(result.getPullRequestId()).isNull();
        }

        @Test
        @DisplayName("未預期錯誤時應回傳 ERROR 並嘗試切回 base branch")
        void shouldReturnErrorAndCleanUpOnException() {
            when(config.isEnabled()).thenReturn(true);
            when(sourceCodeResolver.resolveSourceFiles(any())).thenThrow(new RuntimeException("磁碟壞了"));
            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.attemptFix(sampleBug, sampleRequest);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.ERROR);
            assertThat(result.getFailureReason()).contains("磁碟壞了");
            // finally 區塊應嘗試切回 base branch
            verify(gitService).abandonBranch(anyString());
        }
    }

    @Nested
    @DisplayName("handleReTestResult — 階段 2 重新測試")
    class HandleReTestResultTests {

        @Test
        @DisplayName("re-test 通過時，應關閉 Work Item 並回傳 VERIFIED")
        void shouldResolveWorkItemWhenRetestPasses() {
            E2ETestResult testResult = new E2ETestResult();
            testResult.setStatus(E2ETestResult.TestRunStatus.PASSED);
            testResult.setBugsFound(Collections.emptyList());
            testResult.setTotalSteps(10);

            when(workItemService.resolveWorkItem(eq(123), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.handleReTestResult(123, testResult);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.VERIFIED);
            assertThat(result.getWorkItemId()).isEqualTo(123);
            verify(workItemService).resolveWorkItem(eq(123), contains("Re-test 通過"));
            verify(engineerStatsRecorder).recordAiFixSuccess(123);
        }

        @Test
        @DisplayName("re-test 失敗時，應加註並回傳 FIX_FAILED_TESTS")
        void shouldAddCommentWhenRetestFails() {
            E2ETestResult testResult = new E2ETestResult();
            testResult.setStatus(E2ETestResult.TestRunStatus.FAILED);
            BugFound remainingBug = new BugFound();
            remainingBug.setDescription("仍有問題");
            testResult.setBugsFound(List.of(remainingBug));
            testResult.setPassedSteps(8);
            testResult.setTotalSteps(10);

            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.handleReTestResult(123, testResult);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.FIX_FAILED_TESTS);
            assertThat(result.getFailureReason()).contains("1 個 bug");
            verify(workItemService).addComment(eq(123), contains("Re-test 失敗"));
            verify(workItemService, never()).resolveWorkItem(anyInt(), anyString());
        }

        @Test
        @DisplayName("re-test 狀態 PASSED 但仍有 bug 時，應視為失敗")
        void shouldTreatAsFailedWhenPassedButBugsExist() {
            E2ETestResult testResult = new E2ETestResult();
            testResult.setStatus(E2ETestResult.TestRunStatus.PASSED);
            BugFound bug = new BugFound();
            testResult.setBugsFound(List.of(bug));
            testResult.setPassedSteps(10);
            testResult.setTotalSteps(10);

            when(workItemService.addComment(anyInt(), anyString())).thenReturn(Mono.empty());

            AutoFixResult result = orchestrator.handleReTestResult(123, testResult);

            assertThat(result.getStatus()).isEqualTo(AutoFixStatus.FIX_FAILED_TESTS);
        }
    }
}
