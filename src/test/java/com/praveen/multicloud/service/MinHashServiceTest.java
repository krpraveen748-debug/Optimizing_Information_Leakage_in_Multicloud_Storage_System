package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinHashServiceTest {
    @Test
    void generatesDeterministicSignaturesAndRoundTripsStoredFormat() {
        MinHashService minHash = new MinHashService(128, 42L);
        Set<Long> features = Set.of(10L, 20L, 30L, 40L);

        MinHashService.Signature first = minHash.signature(features);
        MinHashService.Signature second = minHash.signature(new HashSet<>(features));

        assertEquals(first.serialize(), second.serialize());
        assertEquals(first.serialize(), minHash.parse(first.serialize()).serialize());
        assertEquals(1.0, minHash.estimateJaccard(first, second));
    }

    @Test
    void estimatesKnownJaccardAndReturnsZeroForDisjointSets() {
        MinHashService minHash = new MinHashService(256, 2026L);
        Set<Long> firstFeatures = new HashSet<>();
        Set<Long> secondFeatures = new HashSet<>();
        for (long value = 0; value < 100; value++) {
            firstFeatures.add(value);
        }
        for (long value = 50; value < 150; value++) {
            secondFeatures.add(value);
        }

        double estimate = minHash.estimateJaccard(
                minHash.signature(firstFeatures), minHash.signature(secondFeatures));
        double disjoint = minHash.estimateJaccard(
                minHash.signature(Set.of(1L, 2L)), minHash.signature(Set.of(3L, 4L)));

        assertEquals(1.0 / 3.0, estimate, 0.08);
        assertEquals(0.0, disjoint);
    }

    @Test
    void handlesEmptySetsAndRejectsInvalidSettingsOrIncompatibleSignatures() {
        MinHashService minHash = new MinHashService(16, 5L);
        MinHashService.Signature empty = minHash.signature(Set.of());

        assertEquals(1.0, minHash.estimateJaccard(empty, minHash.signature(Set.of())));
        assertEquals(0.0, minHash.estimateJaccard(empty, minHash.signature(Set.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> new MinHashService(7, 5L));
        assertThrows(IllegalArgumentException.class, () -> minHash.signature(Set.of(-1L)));
        assertThrows(IllegalArgumentException.class, () -> minHash.parse("not-a-signature"));
        assertThrows(IllegalArgumentException.class, () ->
                minHash.estimateJaccard(empty, new MinHashService(16, 6L).signature(Set.of())));
        assertTrue(empty.empty());
    }

    @Test
    void combinesChunkSignaturesAsTheMinHashOfTheirFeatureUnion() {
        MinHashService minHash = new MinHashService(64, 13L);
        MinHashService.Signature combined = minHash.union(java.util.List.of(
                minHash.signature(Set.of(1L, 2L)),
                minHash.signature(Set.of(2L, 3L))));

        assertEquals(minHash.signature(Set.of(1L, 2L, 3L)).serialize(), combined.serialize());
    }
}
