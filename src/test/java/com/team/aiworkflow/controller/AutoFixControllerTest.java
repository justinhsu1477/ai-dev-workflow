package com.team.aiworkflow.controller;

import com.team.aiworkflow.config.AutoFixConfig;
import com.team.aiworkflow.model.autofix.AutoFixResult;
import com.team.aiworkflow.model.autofix.AutoFixResult.AutoFixStatus;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.service.autofix.AutoFixOrchestrator;
import com.team.aiworkflow.service.e2e.E2ETestOrchestrator;
import com.team.aiworkflow.service.e2e.TestScopeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutoFixController Re-test API 測試")
class AutoFixControllerTest {

    @Mock private AutoFixConfig autoFixConfig;
    @Mock private E2ETestOrchestrator e2eTestOrchestrator;
    @Mock private AutoFixOrchestrator autoFixOrchestrator;
    @Mock private TestScopeResolver testScopeResolver;

    @InjectMocks
    private AutoFixController controller;

    @Test
    @DisplayName("auto-fix 未啟用時回傳 disabled status")
    void shouldReturnDisabledWhenAutoFixNotEnabled() {
        when(autoFixConfig.isEnabled()).thenReturn(false);

        var response = controller.reTest(123);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("status", "disabled");
    }

    @Test
    @DisplayName("staging-url 未設定時回傳 400")
    void shouldReturn400WhenStagingUrlNotSet() {
        when(autoFixConfig.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "stagingUrl", "");

        var response = controller.reTest(123);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("re-test 通過時應回傳 VERIFIED 並含成功訊息")
    void shouldReturnVerifiedWhenRetestPasses() {
        when(autoFixConfig.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "stagingUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(controller, "appDescription", "OCDS Web");

        TestScopeResolver.TestScope scope = TestScopeResolver.TestScope.builder().build();
        when(testScopeResolver.resolveDeploymentScope()).thenReturn(scope);

        E2ETestResult testResult = new E2ETestResult();
        testResult.setStatus(E2ETestResult.TestRunStatus.PASSED);
        testResult.setBugsFound(Collections.emptyList());
        testResult.setTotalSteps(5);
        testResult.setPassedSteps(5);
        when(e2eTestOrchestrator.runScopedTest(any(), any())).thenReturn(testResult);

        AutoFixResult fixResult = AutoFixResult.builder()
                .workItemId(123).status(AutoFixStatus.VERIFIED).build();
        when(autoFixOrchestrator.handleReTestResult(eq(123), any())).thenReturn(fixResult);

        var response = controller.reTest(123);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("workItemId", 123);
        assertThat(body).containsEntry("message", "Re-test 通過，Work Item 已關閉");
    }

    @Test
    @DisplayName("re-test 失敗時應回傳 FIX_FAILED_TESTS")
    void shouldReturnFailedWhenRetestFails() {
        when(autoFixConfig.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "stagingUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(controller, "appDescription", "OCDS Web");

        when(testScopeResolver.resolveDeploymentScope()).thenReturn(TestScopeResolver.TestScope.builder().build());

        E2ETestResult testResult = new E2ETestResult();
        testResult.setStatus(E2ETestResult.TestRunStatus.FAILED);
        E2ETestResult.BugFound bug = new E2ETestResult.BugFound();
        testResult.setBugsFound(java.util.List.of(bug));
        testResult.setTotalSteps(5);
        testResult.setPassedSteps(3);
        when(e2eTestOrchestrator.runScopedTest(any(), any())).thenReturn(testResult);

        AutoFixResult fixResult = AutoFixResult.builder()
                .workItemId(123).status(AutoFixStatus.FIX_FAILED_TESTS)
                .failureReason("仍有 1 個 bug").build();
        when(autoFixOrchestrator.handleReTestResult(eq(123), any())).thenReturn(fixResult);

        var response = controller.reTest(123);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("status", "FIX_FAILED_TESTS");
        assertThat(body).containsEntry("message", "Re-test 失敗，Work Item 仍開啟");
    }

    @Test
    @DisplayName("內部錯誤時應回傳 500")
    void shouldReturn500OnInternalError() {
        when(autoFixConfig.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "stagingUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(controller, "appDescription", "OCDS Web");

        when(testScopeResolver.resolveDeploymentScope()).thenReturn(TestScopeResolver.TestScope.builder().build());
        when(e2eTestOrchestrator.runScopedTest(any(), any()))
                .thenThrow(new RuntimeException("Playwright 啟動失敗"));

        var response = controller.reTest(123);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }
}
