package com.praveen.multicloud.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProviderLeakageAnalysisResponse(
        long analysisId,
        LocalDateTime createdAt,
        String status,
        Double overallJaccardSimilarity,
        Double overallSimilarityPercent,
        double highSimilarityThreshold,
        int analyzedPairCount,
        int highSimilarityPairCount,
        List<ProviderPairComparisonResponse> pairComparisons) {
}
