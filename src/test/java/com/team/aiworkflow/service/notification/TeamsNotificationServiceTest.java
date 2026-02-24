package com.team.aiworkflow.service.notification;

import com.team.aiworkflow.model.AnalysisResult;
import com.team.aiworkflow.model.stats.EngineerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TeamsNotificationService Teams 通知測試")
class TeamsNotificationServiceTest {

    @Nested
    @DisplayName("webhook URL 未設定的情境")
    class NoWebhookTests {

        @Test
        @DisplayName("notifyAnalysisResult 應安靜跳過")
        void shouldSkipNotificationWhenNoWebhook() {
            TeamsNotificationService service = new TeamsNotificationService("");

            AnalysisResult result = AnalysisResult.builder()
                    .buildNumber("100")
                    .severity(AnalysisResult.Severity.HIGH)
                    .build();

            StepVerifier.create(service.notifyAnalysisResult(result))
                    .verifyComplete();
        }

        @Test
        @DisplayName("sendSimpleMessage 應安靜跳過")
        void shouldSkipSimpleMessageWhenNoWebhook() {
            TeamsNotificationService service = new TeamsNotificationService(null);

            StepVerifier.create(service.sendSimpleMessage("test message"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("sendToWebhook 空 URL 應安靜跳過")
        void shouldSkipWhenTargetWebhookIsEmpty() {
            TeamsNotificationService service = new TeamsNotificationService("");

            StepVerifier.create(service.sendToWebhook("", "test"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("sendToWebhook null URL 應安靜跳過")
        void shouldSkipWhenTargetWebhookIsNull() {
            TeamsNotificationService service = new TeamsNotificationService("");

            StepVerifier.create(service.sendToWebhook(null, "test"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("sendStatsSummary — 統計報告")
    class StatsSummaryTests {

        @Test
        @DisplayName("空統計清單應安靜跳過")
        void shouldSkipWhenStatsListIsEmpty() {
            TeamsNotificationService service = new TeamsNotificationService("");

            StepVerifier.create(service.sendStatsSummary("url", List.of(), "每週"))
                    .verifyComplete();
        }
    }
}
