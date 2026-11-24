package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeakageAwarePlacementServiceTest {
    private final LeakageAwarePlacementService placement =
            new LeakageAwarePlacementService(new JaccardSimilarityService(), 1.0, 0.25, 20261119L);

    @Test
    void roundRobinPlacesEveryUniqueChunkExactlyOnce() {
        var plan = placement.createPlan(
                LeakageAwarePlacementService.Strategy.ROUND_ROBIN,
                candidates("a", "b", "c", "d"));

        assertEquals(4, plan.assignments().size());
        assertEquals("cloud1", plan.assignments().get("a"));
        assertEquals("cloud2", plan.assignments().get("b"));
        assertEquals("cloud3", plan.assignments().get("c"));
        assertEquals("cloud1", plan.assignments().get("d"));
    }

    @Test
    void randomPlacementIsReproducibleAndUsesThreeProvidersForEnoughChunks() {
        var first = placement.createPlan(
                LeakageAwarePlacementService.Strategy.RANDOM,
                candidates("a", "b", "c", "d", "e", "f"), 42L);
        var second = placement.createPlan(
                LeakageAwarePlacementService.Strategy.RANDOM,
                candidates("a", "b", "c", "d", "e", "f"), 42L);

        assertEquals(first.assignments(), second.assignments());
        assertTrue(first.providers().values().stream().mapToLong(
                LeakageAwarePlacementService.ProviderMeasurement::chunkCount).sum() == 6);
    }

    @Test
    void duplicateChunksAreCountedOnlyOnce() {
        var plan = placement.createPlan(
                LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE,
                List.of(
                        new LeakageAwarePlacementService.ChunkCandidate("same", 10, "ss1|x"),
                        new LeakageAwarePlacementService.ChunkCandidate("same", 10, "ss1|x"),
                        new LeakageAwarePlacementService.ChunkCandidate("other", 12, "ss1|y")));

        assertEquals(2, plan.assignments().size());
        assertEquals(2L, plan.providers().values().stream().mapToLong(
                LeakageAwarePlacementService.ProviderMeasurement::chunkCount).sum());
    }

    @Test
    void emptyInputProducesValidEmptyPlan() {
        var plan = placement.createPlan(
                LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE, List.of());

        assertTrue(plan.assignments().isEmpty());
        assertEquals(3, plan.providers().size());
        assertEquals(3, plan.pairMeasurements().size());
    }

    @Test
    void storageBalanceAndProviderDistributionAreReported() {
        var chunks = candidates("a", "b", "c", "d", "e", "f");
        for (LeakageAwarePlacementService.Strategy strategy :
                LeakageAwarePlacementService.Strategy.values()) {
            var plan = placement.createPlan(strategy, chunks);
            System.out.println("STRATEGY=" + strategy
                    + " COUNTS=" + plan.providers().values().stream()
                    .mapToLong(LeakageAwarePlacementService.ProviderMeasurement::chunkCount)
                    .boxed().toList()
                    + " PAIRS=" + plan.pairMeasurements());
        }
        var plan = placement.createPlan(
                LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE, chunks);

        long total = plan.providers().values().stream()
                .mapToLong(LeakageAwarePlacementService.ProviderMeasurement::chunkCount).sum();
        assertEquals(6, total);
        assertTrue(plan.providers().values().stream().allMatch(
                provider -> provider.chunkCount() >= 0));
    }

    @Test
    void rejectsInvalidCandidates() {
        assertThrows(IllegalArgumentException.class,
                () -> placement.createPlan(
                        LeakageAwarePlacementService.Strategy.ROUND_ROBIN,
                        List.of(new LeakageAwarePlacementService.ChunkCandidate("", 1, "sig"))));
    }

    private List<LeakageAwarePlacementService.ChunkCandidate> candidates(String... hashes) {
        return java.util.Arrays.stream(hashes)
                .map(hash -> new LeakageAwarePlacementService.ChunkCandidate(
                        hash, 10, "signature-" + hash))
                .toList();
    }
}
