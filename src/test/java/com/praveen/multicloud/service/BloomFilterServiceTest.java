package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterServiceTest {
    @Test
    void insertedFingerprintsAreAlwaysReportedAsPossibleMembers() {
        BloomFilterService.Filter filter = new BloomFilterService(1024, 5).createFilter();
        filter.add("chunk-a");
        filter.add("chunk-b");

        assertTrue(filter.mightContain("chunk-a"));
        assertTrue(filter.mightContain("chunk-b"));
        assertFalse(filter.mightContain("never-added"));
        assertTrue(filter.estimatedFalsePositiveProbability() >= 0.0);
    }

    @Test
    void tinyFilterDemonstratesAllowedFalsePositivesWithoutFalseNegatives() {
        BloomFilterService.Filter filter = new BloomFilterService(1, 1).createFilter();
        filter.add("inserted");

        assertTrue(filter.mightContain("inserted"));
        assertTrue(filter.mightContain("not-inserted"));
        assertTrue(filter.estimatedFalsePositiveProbability() > 0.0);
    }

    @Test
    void rejectsInvalidSettingsAndFingerprints() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterService(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterService(32, 65));
        BloomFilterService.Filter filter = new BloomFilterService(32, 2).createFilter();
        assertThrows(IllegalArgumentException.class, () -> filter.add(""));
        assertThrows(IllegalArgumentException.class, () -> filter.mightContain(null));
    }
}
