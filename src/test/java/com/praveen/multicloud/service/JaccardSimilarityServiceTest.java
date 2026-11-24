package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JaccardSimilarityServiceTest {
    private final JaccardSimilarityService jaccard = new JaccardSimilarityService();

    @Test
    void calculatesExactSimilarityForIdenticalDisjointAndPartialSets() {
        var identical = jaccard.calculate(Set.of("a", "b"), Set.of("a", "b"));
        var disjoint = jaccard.calculate(Set.of("a"), Set.of("b"));
        var partial = jaccard.calculate(Set.of("a", "b"), Set.of("b", "c"));

        assertEquals(1.0, identical.similarity());
        assertEquals(100.0, identical.percentage());
        assertEquals(0.0, disjoint.similarity());
        assertEquals(1.0 / 3.0, partial.similarity());
        assertEquals(1, partial.intersectionCount());
        assertEquals(3, partial.unionCount());
    }

    @Test
    void safelyHandlesEmptyInputsAndIgnoresDuplicateFingerprints() {
        var empty = jaccard.calculate(List.of(), List.of());
        var duplicates = jaccard.calculate(
                List.of("a", "a", "b"), List.of("a", "a", "c", "c"));

        assertEquals(1.0, empty.similarity());
        assertEquals(0, empty.unionCount());
        assertEquals(1.0 / 3.0, duplicates.similarity());
        assertEquals(1, duplicates.intersectionCount());
        assertEquals(3, duplicates.unionCount());
    }

    @Test
    void rejectsInvalidFingerprintCollections() {
        assertThrows(NullPointerException.class, () -> jaccard.calculate(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> jaccard.calculate(List.of(" "), List.of()));
    }
}
