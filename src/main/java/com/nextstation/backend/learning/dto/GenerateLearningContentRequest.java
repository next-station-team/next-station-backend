package com.nextstation.backend.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프론트가 보내는 요청. 강의 노트 텍스트(최대 약 5,000자).
 */
public record GenerateLearningContentRequest(
        @NotBlank(message = "강의 노트를 입력해 주세요.")
        @Size(max = 5000, message = "강의 노트는 5,000자 이내로 입력해 주세요.")
        String text
) {
}
