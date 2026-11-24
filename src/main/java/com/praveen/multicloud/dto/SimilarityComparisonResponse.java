package com.praveen.multicloud.dto;

public record SimilarityComparisonResponse(
        long fileId,
        long candidateFileId,
        String status,
        String message,
        Double estimatedJaccardSimilarity,
        Double similarityPercent,
        int minHashPermutationCount,
        int sourceChunkCount,
        int candidateChunkCount,
        int bloomPositiveCount,
        int exactMatchingChunkCount,
        double bloomExpectedFalsePositiveRate) {
}
