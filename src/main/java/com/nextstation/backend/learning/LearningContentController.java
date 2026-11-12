package com.nextstation.backend.learning;

import com.nextstation.backend.learning.dto.GenerateLearningContentRequest;
import com.nextstation.backend.learning.dto.LearningContentResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F-05 학습 콘텐츠 생성 API.
 * POST /api/learning-content/generate
 */
@RestController
@RequestMapping("/api/learning-content")
public class LearningContentController {

    private final LearningContentService learningContentService;

    public LearningContentController(LearningContentService learningContentService) {
        this.learningContentService = learningContentService;
    }

    @PostMapping("/generate")
    public LearningContentResponse generate(@Valid @RequestBody GenerateLearningContentRequest request) {
        return learningContentService.generate(request.text());
    }
}
