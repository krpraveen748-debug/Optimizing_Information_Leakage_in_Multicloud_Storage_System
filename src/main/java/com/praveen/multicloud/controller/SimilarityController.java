package com.praveen.multicloud.controller;

import com.praveen.multicloud.dto.SimilarityComparisonRequest;
import com.praveen.multicloud.dto.SimilarityComparisonResponse;
import com.praveen.multicloud.security.AuthenticatedUser;
import com.praveen.multicloud.service.SimilarityAnalysisService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/similarity")
public class SimilarityController {
    private final SimilarityAnalysisService similarity;

    public SimilarityController(SimilarityAnalysisService similarity) {
        this.similarity = similarity;
    }

    @PostMapping("/compare")
    public SimilarityComparisonResponse compare(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SimilarityComparisonRequest request) {
        return similarity.compare(user.getId(), request.fileId(), request.candidateFileId());
    }

    @GetMapping("/files/{fileId}/compare/{candidateFileId}")
    public SimilarityComparisonResponse viewComparison(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long fileId,
            @PathVariable long candidateFileId) {
        return similarity.compare(user.getId(), fileId, candidateFileId);
    }
}
