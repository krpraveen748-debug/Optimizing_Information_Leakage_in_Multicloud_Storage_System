package com.praveen.multicloud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SimilarityComparisonRequest(
        @NotNull @Positive Long fileId,
        @NotNull @Positive Long candidateFileId) {
}
