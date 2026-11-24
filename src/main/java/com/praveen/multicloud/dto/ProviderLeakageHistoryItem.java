package com.praveen.multicloud.dto;

import java.time.LocalDateTime;

public record ProviderLeakageHistoryItem(
        long analysisId,
        LocalDateTime createdAt,
        String status,
        Double overallJaccardSimilarity,
        Double overallSimilarityPercent,
        double highSimilarityThreshold,
        int analyzedPairCount,
        int highSimilarityPairCount) {
}
