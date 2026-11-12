package com.nextstation.backend.learning.dto;

import java.util.List;

/**
 * 프론트로 내려주는 응답 (samples/api-response.example.json 모양).
 */
public record LearningContentResponse(
        String contentId,
        String title,
        String model,
        String createdAt,
        List<TopicDto> topics,
        List<QuizDto> quizzes,
        List<TermDto> terms,
        List<BlockDto> blocks
) {

    public record TopicDto(String id, String title, List<CardDto> summaryCards, String ttsScript) {
    }

    public record CardDto(String id, String heading, String body) {
    }

    public record QuizDto(
            String id,
            String topicId,
            String question,
            List<String> options,
            int answerIndex,
            String explanation,
            String evidence
    ) {
    }

    public record TermDto(String id, String topicId, String term, String definition) {
    }

    /** type: SUMMARY, AUDIO, QUIZ */
    public record BlockDto(String id, String topicId, String type, String title, int itemCount, int estimatedSeconds) {
    }
}
