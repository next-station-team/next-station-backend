package com.nextstation.backend.learning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gemini generateContent REST API 호출.
 * API 키와 모델명은 환경변수(GEMINI_API_KEY, GEMINI_MODEL)로 받는다.
 * 429(요청 과다), 503(과부하)이면 잠시 기다렸다가 1회 재시도한다.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 503);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final long retryDelayMs;

    public GeminiClient(
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${GEMINI_MODEL:gemini-3.1-flash-lite}") String model,
            @Value("${GEMINI_TIMEOUT_SECONDS:45}") long timeoutSeconds,
            @Value("${GEMINI_RETRY_DELAY_MS:2000}") long retryDelayMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.retryDelayMs = retryDelayMs;
    }

    public String model() {
        return model;
    }

    /**
     * 시스템 프롬프트와 사용자 입력을 보내고, 스키마에 맞춰 생성된 JSON 문자열을 돌려준다.
     */
    public String generateJson(String systemPrompt, String userText, Map<String, Object> responseSchema) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ContentGenerationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 생성 설정이 아직 준비되지 않았어요.");
        }

        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userText))
                )),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseJsonSchema", responseSchema
                )
        );

        GeminiResponse response = callWithRetry(body);

        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new ContentGenerationException(HttpStatus.BAD_GATEWAY, "AI가 결과를 만들지 못했어요.");
        }

        Candidate candidate = response.candidates().get(0);
        if (candidate.content() == null || candidate.content().parts() == null) {
            log.warn("Gemini 결과 없음: finishReason={}", candidate.finishReason());
            throw new ContentGenerationException(HttpStatus.BAD_GATEWAY, "AI가 결과를 만들지 못했어요.");
        }

        return candidate.content().parts().stream()
                .map(Part::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    private GeminiResponse callWithRetry(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post()
                        .uri("/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(GeminiResponse.class);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (attempt == 1 && RETRYABLE_STATUS.contains(status)) {
                    log.warn("Gemini {} 응답, {}ms 후 재시도", status, retryDelayMs);
                    sleep(retryDelayMs);
                    continue;
                }
                log.warn("Gemini 응답 오류: status={}, attempt={}", status, attempt);
                if (RETRYABLE_STATUS.contains(status)) {
                    throw new ContentGenerationException(HttpStatus.SERVICE_UNAVAILABLE,
                            "AI 서비스 사용량이 많아요. 잠시 후 다시 시도해 주세요.");
                }
                throw new ContentGenerationException(HttpStatus.BAD_GATEWAY,
                        "AI 생성 서비스 호출에 실패했어요. 잠시 후 다시 시도해 주세요.");
            } catch (RestClientException e) {
                log.warn("Gemini 호출 실패 (타임아웃 또는 네트워크): {}", e.getMessage());
                throw new ContentGenerationException(HttpStatus.GATEWAY_TIMEOUT,
                        "AI 생성이 너무 오래 걸려요. 잠시 후 다시 시도해 주세요.");
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentGenerationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 생성이 중단됐어요. 다시 시도해 주세요.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {
    }
}