package com.nextstation.backend.learning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Gemini가 돌려주는 JSON (schema/llm-output.schema.json 모양).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmOutput(String title, List<Topic> topics, List<Quiz> quizzes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Topic(String title, List<SummaryCard> summaryCards, String ttsScript, List<Term> terms) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SummaryCard(String heading, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Term(String term, String definition) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quiz(
            Integer topicIndex,
            String question,
            List<String> options,
            Integer answerIndex,
            String explanation,
            String evidence
    ) {
    }
}
