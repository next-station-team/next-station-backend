package com.nextstation.backend.learning;

import com.nextstation.backend.learning.dto.LlmOutput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Gemini 출력이 스키마 규칙을 실제로 지켰는지 한 번 더 확인한다.
 * 문제 목록이 비어 있으면 통과.
 */
@Component
public class LlmOutputValidator {

    public List<String> validate(LlmOutput output) {
        List<String> errors = new ArrayList<>();
        if (output == null) {
            errors.add("출력이 비어 있음");
            return errors;
        }
        if (isBlank(output.title())) {
            errors.add("title 없음");
        }

        List<LlmOutput.Topic> topics = output.topics();
        if (topics == null || topics.size() < 2 || topics.size() > 4) {
            errors.add("topics는 2~4개여야 함");
            return errors;
        }

        for (int i = 0; i < topics.size(); i++) {
            LlmOutput.Topic topic = topics.get(i);
            String where = "topics[" + i + "]";
            if (topic == null) {
                errors.add(where + " 비어 있음");
                continue;
            }
            if (isBlank(topic.title())) {
                errors.add(where + ".title 없음");
            }
            if (isBlank(topic.ttsScript())) {
                errors.add(where + ".ttsScript 없음");
            }
            if (topic.summaryCards() == null || topic.summaryCards().isEmpty() || topic.summaryCards().size() > 2) {
                errors.add(where + ".summaryCards는 1~2개여야 함");
            } else {
                for (LlmOutput.SummaryCard card : topic.summaryCards()) {
                    if (card == null || isBlank(card.heading()) || isBlank(card.body())) {
                        errors.add(where + ".summaryCards에 빈 카드 있음");
                    }
                }
            }
            if (topic.terms() == null || topic.terms().isEmpty() || topic.terms().size() > 3) {
                errors.add(where + ".terms는 1~3개여야 함");
            } else {
                for (LlmOutput.Term term : topic.terms()) {
                    if (term == null || isBlank(term.term()) || isBlank(term.definition())) {
                        errors.add(where + ".terms에 빈 용어 있음");
                    }
                }
            }
        }

        List<LlmOutput.Quiz> quizzes = output.quizzes();
        if (quizzes == null || quizzes.size() != 5) {
            errors.add("quizzes는 정확히 5개여야 함");
            return errors;
        }

        for (int i = 0; i < quizzes.size(); i++) {
            LlmOutput.Quiz quiz = quizzes.get(i);
            String where = "quizzes[" + i + "]";
            if (quiz == null) {
                errors.add(where + " 비어 있음");
                continue;
            }
            if (quiz.topicIndex() == null || quiz.topicIndex() < 0 || quiz.topicIndex() >= topics.size()) {
                errors.add(where + ".topicIndex가 topics 범위를 벗어남");
            }
            if (isBlank(quiz.question()) || isBlank(quiz.explanation()) || isBlank(quiz.evidence())) {
                errors.add(where + " 질문/해설/근거 중 빈 값 있음");
            }
            if (quiz.options() == null || quiz.options().size() != 4) {
                errors.add(where + ".options는 4개여야 함");
            } else {
                if (quiz.options().stream().anyMatch(this::isBlank)) {
                    errors.add(where + ".options에 빈 보기 있음");
                }
                if (new HashSet<>(quiz.options()).size() != 4) {
                    errors.add(where + ".options에 중복 보기 있음");
                }
            }
            if (quiz.answerIndex() == null || quiz.answerIndex() < 0 || quiz.answerIndex() > 3) {
                errors.add(where + ".answerIndex는 0~3이어야 함");
            }
        }

        return errors;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
