package com.nextstation.backend.learning;

import com.nextstation.backend.learning.dto.LearningContentResponse;
import com.nextstation.backend.learning.dto.LearningContentResponse.BlockDto;
import com.nextstation.backend.learning.dto.LearningContentResponse.CardDto;
import com.nextstation.backend.learning.dto.LearningContentResponse.QuizDto;
import com.nextstation.backend.learning.dto.LearningContentResponse.TermDto;
import com.nextstation.backend.learning.dto.LearningContentResponse.TopicDto;
import com.nextstation.backend.learning.dto.LlmOutput;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Gemini 출력(LlmOutput)을 프론트용 응답으로 바꾼다.
 * - id 부여: 주제 t1, 카드 t1-c1, 퀴즈 q1, 용어 w1
 * - topicIndex(0부터) -> topicId("t1")
 * - 주제별 terms를 하나의 목록으로 모음
 * - 주제마다 SUMMARY / AUDIO / QUIZ 블록과 예상 소요 시간 계산
 */
@Component
public class LearningContentMapper {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // 예상 소요 시간 규칙 (F-06 콘텐츠 구성에서 사용). 탑승 테스트 후 조정 가능.
    private static final int SUMMARY_SECONDS = 30;
    private static final int SECONDS_PER_QUIZ = 30;
    private static final double TTS_CHARS_PER_SECOND = 7.0;
    private static final int TIME_UNIT_SECONDS = 30;

    public LearningContentResponse toResponse(LlmOutput output, String model) {
        List<TopicDto> topics = new ArrayList<>();
        List<TermDto> terms = new ArrayList<>();
        List<BlockDto> blocks = new ArrayList<>();
        List<LlmOutput.Topic> llmTopics = output.topics();

        // 주제별 퀴즈 개수 (QUIZ 블록 itemCount용)
        int[] quizCountByTopic = new int[llmTopics.size()];
        for (LlmOutput.Quiz quiz : output.quizzes()) {
            quizCountByTopic[quiz.topicIndex()]++;
        }

        int termSeq = 1;
        for (int i = 0; i < llmTopics.size(); i++) {
            LlmOutput.Topic topic = llmTopics.get(i);
            String topicId = topicId(i);

            List<CardDto> cards = new ArrayList<>();
            for (int c = 0; c < topic.summaryCards().size(); c++) {
                LlmOutput.SummaryCard card = topic.summaryCards().get(c);
                cards.add(new CardDto(topicId + "-c" + (c + 1), card.heading(), card.body()));
            }
            topics.add(new TopicDto(topicId, topic.title(), cards, topic.ttsScript()));

            for (LlmOutput.Term term : topic.terms()) {
                terms.add(new TermDto("w" + termSeq++, topicId, term.term(), term.definition()));
            }

            blocks.add(new BlockDto(topicId + "-summary", topicId, "SUMMARY", topic.title(),
                    cards.size(), SUMMARY_SECONDS));
            blocks.add(new BlockDto(topicId + "-audio", topicId, "AUDIO", topic.title(),
                    1, ttsSeconds(topic.ttsScript())));
            if (quizCountByTopic[i] > 0) {
                blocks.add(new BlockDto(topicId + "-quiz", topicId, "QUIZ", topic.title(),
                        quizCountByTopic[i], quizCountByTopic[i] * SECONDS_PER_QUIZ));
            }
        }

        List<QuizDto> quizzes = new ArrayList<>();
        for (int q = 0; q < output.quizzes().size(); q++) {
            LlmOutput.Quiz quiz = output.quizzes().get(q);
            quizzes.add(new QuizDto(
                    "q" + (q + 1),
                    topicId(quiz.topicIndex()),
                    quiz.question(),
                    quiz.options(),
                    quiz.answerIndex(),
                    quiz.explanation(),
                    quiz.evidence()
            ));
        }

        String createdAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS));

        return new LearningContentResponse(
                UUID.randomUUID().toString(),
                output.title(),
                model,
                createdAt,
                topics,
                quizzes,
                terms,
                blocks
        );
    }

    private String topicId(int topicIndex) {
        return "t" + (topicIndex + 1);
    }

    /** 낭독 대본 길이로 재생 시간을 추정하고 30초 단위로 올림 (최소 30초). */
    private int ttsSeconds(String script) {
        double seconds = script.length() / TTS_CHARS_PER_SECOND;
        int units = (int) Math.ceil(seconds / TIME_UNIT_SECONDS);
        return Math.max(1, units) * TIME_UNIT_SECONDS;
    }
}
