package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilaritySignatureServiceTest {
    @Test
    void generatesDeterministicSignaturesWithoutRetainingPlaintext() {
        MinHashService minHash = new MinHashService(128, 91L);
        SimilaritySignatureService signatures = new SimilaritySignatureService(minHash, 8);
        byte[] chunk = "a repeated pattern in a chunk".getBytes(StandardCharsets.UTF_8);

        String first = signatures.generate(chunk).serialize();
        String second = signatures.generate(chunk.clone()).serialize();

        assertEquals(first, second);
        String stored = signatures.generateSerialized(chunk);
        assertTrue(signatures.isCompatible(stored));
        assertFalse(signatures.isCompatible("raw file content"));
        assertFalse(new SimilaritySignatureService(minHash, 9).isCompatible(stored));
    }

    @Test
    void similarAndDifferentChunkContentProducesDifferentSimilarityEstimates() {
        MinHashService minHash = new MinHashService(256, 19L);
        SimilaritySignatureService signatures = new SimilaritySignatureService(minHash, 8);
        byte[] original = new byte[2048];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 31 + i / 7);
        }
        byte[] nearby = original.clone();
        Arrays.fill(nearby, 1900, nearby.length, (byte) 0x7f);
        byte[] different = new byte[original.length];
        Arrays.fill(different, (byte) 0x55);

        double nearbySimilarity = minHash.estimateJaccard(
                signatures.generate(original), signatures.generate(nearby));
        double differentSimilarity = minHash.estimateJaccard(
                signatures.generate(original), signatures.generate(different));

        assertTrue(nearbySimilarity > 0.65);
        assertTrue(differentSimilarity < nearbySimilarity);
    }

    @Test
    void handlesEmptyAndShortChunksAndRejectsInvalidConfiguration() {
        MinHashService minHash = new MinHashService(32, 3L);
        SimilaritySignatureService signatures = new SimilaritySignatureService(minHash, 8);

        assertTrue(signatures.generate(new byte[0]).empty());
        assertFalse(signatures.generate(new byte[]{1, 2, 3}).empty());
        assertThrows(IllegalArgumentException.class, () -> new SimilaritySignatureService(minHash, 1));
        assertThrows(IllegalArgumentException.class, () -> signatures.generate(null));
    }
}
