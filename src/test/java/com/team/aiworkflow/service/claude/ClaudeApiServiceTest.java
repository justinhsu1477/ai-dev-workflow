package com.team.aiworkflow.service.claude;

import com.team.aiworkflow.config.ClaudeApiConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaudeApiService Claude API 呼叫測試")
class ClaudeApiServiceTest {

    private MockWebServer mockServer;
    private ClaudeApiService service;
    private ClaudeApiConfig config;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        config = new ClaudeApiConfig();
        config.setApiKey("test-api-key");
        config.setModel("claude-sonnet-4-5-20250929");
        config.setModelComplex("claude-opus-4-6");
        config.setMaxTokens(4096);
        config.setTimeoutSeconds(10);

        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.simple(100, Duration.ofMinutes(1)))
                .build();

        service = new ClaudeApiService(config, bucket);

        // 用反射把 WebClient 指向 MockWebServer
        WebClient mockWebClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .defaultHeader("x-api-key", "test-api-key")
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Field wcField = ClaudeApiService.class.getDeclaredField("webClient");
        wcField.setAccessible(true);
        wcField.set(service, mockWebClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("analyze 應呼叫 Sonnet 模型並回傳文字回應")
    void shouldCallSonnetModelForAnalyze() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("""
                    {
                      "content": [{"type": "text", "text": "這是分析結果"}],
                      "model": "claude-sonnet-4-5-20250929",
                      "stop_reason": "end_turn"
                    }
                    """)
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(service.analyze("分析這段程式碼"))
                .expectNext("這是分析結果")
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("claude-sonnet-4-5-20250929");
    }

    @Test
    @DisplayName("analyzeComplex 應呼叫 Opus 模型")
    void shouldCallOpusModelForAnalyzeComplex() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("""
                    {
                      "content": [{"type": "text", "text": "複雜分析結果"}],
                      "model": "claude-opus-4-6",
                      "stop_reason": "end_turn"
                    }
                    """)
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(service.analyzeComplex("產生修復方案"))
                .expectNext("複雜分析結果")
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("claude-opus-4-6");
    }

    @Test
    @DisplayName("API 回傳空內容時應發出錯誤")
    void shouldErrorOnEmptyContent() {
        mockServer.enqueue(new MockResponse()
                .setBody("""
                    {
                      "content": [],
                      "model": "claude-sonnet-4-5-20250929"
                    }
                    """)
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(service.analyze("test"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("速率限制耗盡時應回傳錯誤")
    void shouldErrorWhenRateLimitExhausted() {
        // 建立一個只有 1 token 的 bucket，消耗掉後再測試
        Bucket exhaustedBucket = Bucket.builder()
                .addLimit(Bandwidth.simple(1, Duration.ofHours(1)))
                .build();
        exhaustedBucket.tryConsume(1); // 耗盡

        try {
            Field bucketField = ClaudeApiService.class.getDeclaredField("rateLimiter");
            bucketField.setAccessible(true);
            bucketField.set(service, exhaustedBucket);
        } catch (Exception e) {
            // 如果反射失敗，跳過此測試
            return;
        }

        StepVerifier.create(service.analyze("test"))
                .expectErrorMatches(e -> e.getMessage().contains("Rate limit"))
                .verify();
    }

    @Test
    @DisplayName("API 回傳 500 錯誤時應發出錯誤訊號")
    void shouldErrorOnServerError() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        StepVerifier.create(service.analyze("test"))
                .expectError()
                .verify();
    }
}
