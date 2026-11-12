package com.nextstation.backend.learning;

import com.nextstation.backend.learning.dto.LearningContentResponse;
import com.nextstation.backend.learning.dto.LlmOutput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 1) Gemini 호출 -> 2) JSON 파싱 -> 3) 규칙 검증
 * 형식이 깨지면 1회만 다시 요청하고, 그래도 실패하면 오류를 낸다.
 * 생성 결과는 서버에 저장하지 않는다 (프론트 IndexedDB에 저장).
 */
@Service
public class LearningContentService {

    private static final Logger log = LoggerFactory.getLogger(LearningContentService.class);
    private static final int MAX_ATTEMPTS = 2;

    private final GeminiClient geminiClient;
    private final LlmOutputValidator validator;
    private final LearningContentMapper mapper;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> responseSchema;
    private final String systemPrompt;

    public LearningContentService(
            GeminiClient geminiClient,
            LlmOutputValidator validator,
            LearningContentMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.geminiClient = geminiClient;
        this.validator = validator;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.responseSchema = loadSchema();
        this.systemPrompt = loadText("gemini/system-prompt.txt");
    }

    public LearningContentResponse generate(String noteText) {
        String userText = "다음 강의 노트로 학습 콘텐츠를 만들어 주세요.\n\n<note>\n" + noteText + "\n</note>";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.currentTimeMillis();
            String json = geminiClient.generateJson(systemPrompt, userText, responseSchema);
            long elapsed = System.currentTimeMillis() - start;

            try {
                LlmOutput output = objectMapper.readValue(json, LlmOutput.class);
                List<String> errors = validator.validate(output);
                if (errors.isEmpty()) {
                    log.info("학습 콘텐츠 생성 성공: attempt={}, {}ms", attempt, elapsed);
                    return mapper.toResponse(output, geminiClient.model());
                }
                log.warn("형식 검증 실패: attempt={}, errors={}", attempt, errors);
            } catch (JacksonException e) {
                log.warn("JSON 파싱 실패: attempt={}, {}", attempt, e.getOriginalMessage());
            }
        }

        throw new ContentGenerationException(HttpStatus.BAD_GATEWAY,
                "학습 콘텐츠를 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSchema() {
        try (InputStream in = new ClassPathResource("gemini/llm-output.schema.json").getInputStream()) {
            return objectMapper.readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("gemini/llm-output.schema.json을 읽을 수 없습니다.", e);
        }
    }

    private String loadText(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path + "을 읽을 수 없습니다.", e);
        }
    }
}
