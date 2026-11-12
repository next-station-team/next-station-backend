package com.nextstation.backend.learning;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 학습 콘텐츠 API에서 난 생성 실패를 JSON 오류 응답으로 바꾼다.
 * learning 패키지에만 적용되어 다른 API의 오류 처리에는 영향을 주지 않는다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = LearningContentController.class)
public class LearningContentExceptionHandler {

    @ExceptionHandler(ContentGenerationException.class)
    public ResponseEntity<Map<String, String>> handle(ContentGenerationException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of(
                        "code", "CONTENT_GENERATION_FAILED",
                        "message", e.getMessage(),
                        "timestamp", java.time.Instant.now().toString()
                ));
    }
}
