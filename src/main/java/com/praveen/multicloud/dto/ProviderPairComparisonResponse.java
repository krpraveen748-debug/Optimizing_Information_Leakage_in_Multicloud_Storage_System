package com.praveen.multicloud.dto;

public record ProviderPairComparisonResponse(
        String providerA,
        String providerB,
        long distinctFingerprintCountA,
        long distinctFingerprintCountB,
        long sharedFingerprintCount,
        long unionFingerprintCount,
        Double jaccardSimilarity,
        Double sharedFingerprintPercentage,
        boolean dataAvailable,
        boolean highSimilarity) {
}
