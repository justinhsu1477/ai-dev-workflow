package com.team.aiworkflow.service.analysis;

import com.team.aiworkflow.model.AnalysisResult;
import com.team.aiworkflow.service.azuredevops.PipelineService;
import com.team.aiworkflow.service.azuredevops.WorkItemService;
import com.team.aiworkflow.service.claude.ClaudeApiService;
import com.team.aiworkflow.service.claude.PromptBuilder;
import com.team.aiworkflow.service.claude.ResponseParser;
import com.team.aiworkflow.service.notification.TeamsNotificationService;
import com.team.aiworkflow.util.LogTruncator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailureAnalysisService CI 失敗分析測試")
class FailureAnalysisServiceTest {

    @Mock private PipelineService pipelineService;
    @Mock private WorkItemService workItemService;
    @Mock private ClaudeApiService claudeApiService;
    @Mock private PromptBuilder promptBuilder;
    @Mock private ResponseParser responseParser;
    @Mock private TeamsNotificationService teamsNotificationService;
    @Mock private LogTruncator logTruncator;

    @InjectMocks
    private FailureAnalysisService service;

    @Test
    @DisplayName("analyzeManually 應串接完整分析流程並回傳結果")
    void shouldCompleteFullAnalysisFlowManually() {
        when(pipelineService.getTestLogs(100)).thenReturn(Mono.just("NullPointerException at line 42"));
        when(pipelineService.getBuildChanges(100)).thenReturn(Mono.just("+ modified code"));
        when(logTruncator.truncate(anyString())).thenReturn("truncated log");
        when(promptBuilder.buildFailureAnalysisPrompt(anyString(), anyString(), anyString()))
                .thenReturn("analysis prompt");
        when(claudeApiService.analyze("analysis prompt")).thenReturn(Mono.just("ai response"));

        AnalysisResult mockResult = AnalysisResult.builder()
                .buildId("100")
                .rootCause("NullPointerException")
                .severity(AnalysisResult.Severity.HIGH)
                .summary("NPE in UserService")
                .build();
        when(responseParser.parseFailureAnalysis("ai response", "100", "Build-100", "main", "abc"))
                .thenReturn(mockResult);

        StepVerifier.create(service.analyzeManually(100, "Build-100", "main", "abc"))
                .assertNext(result -> {
                    assertThat(result.getRootCause()).isEqualTo("NullPointerException");
                    assertThat(result.getSeverity()).isEqualTo(AnalysisResult.Severity.HIGH);
                    assertThat(result.getAnalyzedAt()).isNotNull();
                    assertThat(result.getModelUsed()).isEqualTo("claude-sonnet-4-5");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Pipeline 取 log 失敗時應傳播錯誤")
    void shouldPropagateErrorWhenLogFetchFails() {
        when(pipelineService.getTestLogs(100))
                .thenReturn(Mono.error(new RuntimeException("Azure DevOps 連線失敗")));
        when(pipelineService.getBuildChanges(100)).thenReturn(Mono.just("changes"));

        StepVerifier.create(service.analyzeManually(100, "B100", "main", "c1"))
                .expectError(RuntimeException.class)
                .verify();
    }
}
