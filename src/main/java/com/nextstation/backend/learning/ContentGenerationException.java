package com.nextstation.backend.learning;

import org.springframework.http.HttpStatus;

/**
 * 학습 콘텐츠 생성 실패. 상태 코드와 사용자에게 보여줄 메시지를 담는다.
 */
public class ContentGenerationException extends RuntimeException {

    private final HttpStatus status;

    public ContentGenerationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
