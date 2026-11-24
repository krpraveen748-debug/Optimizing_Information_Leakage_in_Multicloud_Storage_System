package com.praveen.multicloud.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class SimilaritySignatureService {
    private static final int MAX_SAMPLED_FEATURES = 512;

    private final MinHashService minHash;
    private final int shingleSize;

    public SimilaritySignatureService(
            MinHashService minHash,
            @Value("${app.similarity.shingle-size:8}") int shingleSize) {
        if (shingleSize < 2 || shingleSize > 32) {
            throw new IllegalArgumentException("Similarity shingle size must be between 2 and 32 bytes.");
        }
        this.minHash = minHash;
        this.shingleSize = shingleSize;
    }

    public MinHashService.Signature generate(byte[] plaintextChunk) {
        if (plaintextChunk == null) {
            throw new IllegalArgumentException("Chunk content is required.");
        }
        if (plaintextChunk.length == 0) {
            return minHash.signature(Set.of());
        }

        Set<Long> features = new HashSet<>();
        int lastStart = Math.max(0, plaintextChunk.length - shingleSize);
        int candidateCount = lastStart + 1;
        int samplingStep = Math.max(1, (candidateCount + MAX_SAMPLED_FEATURES - 1) / MAX_SAMPLED_FEATURES);
        for (int offset = 0; offset <= lastStart; offset += samplingStep) {
            int length = Math.min(shingleSize, plaintextChunk.length - offset);
            features.add(fingerprint(plaintextChunk, offset, length));
        }
        if (lastStart % samplingStep != 0) {
            features.add(fingerprint(plaintextChunk, lastStart,
                    Math.min(shingleSize, plaintextChunk.length - lastStart)));
        }
        return minHash.signature(features);
    }

    public String generateSerialized(byte[] plaintextChunk) {
        return "ss1|" + shingleSize + "|" + generate(plaintextChunk).serialize();
    }

    public MinHashService.Signature parse(String serializedSignature) {
        if (serializedSignature == null || serializedSignature.length() > 4096) {
            throw new IllegalArgumentException("Stored similarity signature is invalid.");
        }
        String[] parts = serializedSignature.split("\\|", 3);
        if (parts.length != 3 || !"ss1".equals(parts[0])) {
            throw new IllegalArgumentException("Stored similarity signature has an unsupported format.");
        }
        try {
            if (Integer.parseInt(parts[1]) != shingleSize) {
                throw new IllegalArgumentException("Stored similarity signature uses different settings.");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Stored similarity signature is invalid.", e);
        }
        return minHash.parse(parts[2]);
    }

    public boolean isCompatible(String serializedSignature) {
        try {
            parse(serializedSignature);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static long fingerprint(byte[] data, int offset, int length) {
        long hash = 0xcbf29ce484222325L;
        for (int i = offset; i < offset + length; i++) {
            hash ^= Byte.toUnsignedLong(data[i]);
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash & 0xffff_ffffL;
    }
}
