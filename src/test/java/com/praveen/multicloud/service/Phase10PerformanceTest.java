package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase10PerformanceTest {
    @Test
    void reproducibleMicroBenchmarkReportsActualMeasurements() {
        JaccardSimilarityService jaccard = new JaccardSimilarityService();
        LeakageAwarePlacementService placement =
                new LeakageAwarePlacementService(jaccard, 1.0, 0.25, 20261119L);
        Random random = new Random(20261119L);

        List<LeakageAwarePlacementService.ChunkCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            byte[] data = new byte[4096];
            random.nextBytes(data);
            candidates.add(new LeakageAwarePlacementService.ChunkCandidate(
                    ChunkingService.sha256(data),
                    data.length,
                    "signature-" + i));
        }

        long roundStart = System.nanoTime();
        var roundRobin = placement.createPlan(
                LeakageAwarePlacementService.Strategy.ROUND_ROBIN, candidates);
        long roundNanos = System.nanoTime() - roundStart;

        long randomStart = System.nanoTime();
        var randomPlan = placement.createPlan(
                LeakageAwarePlacementService.Strategy.RANDOM, candidates);
        long randomNanos = System.nanoTime() - randomStart;

        long awareStart = System.nanoTime();
        var awarePlan = placement.createPlan(
                LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE, candidates);
        long awareNanos = System.nanoTime() - awareStart;

        Set<String> first = new LinkedHashSet<>();
        Set<String> second = new LinkedHashSet<>();
        for (int i = 0; i < 300; i++) {
            first.add("fingerprint-" + i);
            if (i % 3 != 0) second.add("fingerprint-" + i);
        }
        long jaccardStart = System.nanoTime();
        var jaccardResult = jaccard.calculate(first, second);
        long jaccardNanos = System.nanoTime() - jaccardStart;

        assertTrue(roundRobin.assignments().size() == 300);
        assertTrue(randomPlan.assignments().size() == 300);
        assertTrue(awarePlan.assignments().size() == 300);
        assertTrue(jaccardResult.similarity() >= 0.0);

        System.out.printf(
                "PHASE10_BENCHMARK dataset=300-random-4096-byte-chunks "
                        + "round_robin_ns=%d random_ns=%d leakage_aware_ns=%d "
                        + "jaccard_ns=%d jaccard=%.6f%n",
                roundNanos, randomNanos, awareNanos,
                jaccardNanos, jaccardResult.similarity());
        for (var plan : List.of(roundRobin, randomPlan, awarePlan)) {
            System.out.printf("PHASE10_BALANCE strategy=%s counts=%s%n",
                    plan.strategy(),
                    plan.providers().values().stream()
                            .map(provider -> Long.toString(provider.chunkCount()))
                            .toList());
        }
    }
}
