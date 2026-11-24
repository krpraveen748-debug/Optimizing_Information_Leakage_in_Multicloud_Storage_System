package com.praveen.multicloud.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;

@Service
public class BloomFilterService {
    private static final int MAX_BIT_SIZE = 100_000_000;
    private static final int MAX_HASH_COUNT = 64;

    private final int bitSize;
    private final int hashCount;

    public BloomFilterService(
            @Value("${app.similarity.bloom.bit-size:1048576}") int bitSize,
            @Value("${app.similarity.bloom.hash-count:7}") int hashCount) {
        if (bitSize < 1 || bitSize > MAX_BIT_SIZE) {
            throw new IllegalArgumentException("Bloom Filter bit size must be between 1 and 100000000.");
        }
        if (hashCount < 1 || hashCount > MAX_HASH_COUNT) {
            throw new IllegalArgumentException("Bloom Filter hash count must be between 1 and 64.");
        }
        this.bitSize = bitSize;
        this.hashCount = hashCount;
    }

    public Filter createFilter() {
        return new Filter(bitSize, hashCount);
    }

    public int bitSize() {
        return bitSize;
    }

    public int hashCount() {
        return hashCount;
    }

    public static final class Filter {
        private final int bitSize;
        private final int hashCount;
        private final BitSet bits;
        private long insertionCount;

        private Filter(int bitSize, int hashCount) {
            this.bitSize = bitSize;
            this.hashCount = hashCount;
            this.bits = new BitSet(bitSize);
        }

        public void add(String fingerprint) {
            byte[] value = hashInput(fingerprint);
            long[] seeds = seeds(value);
            for (int i = 0; i < hashCount; i++) {
                bits.set(index(seeds[0] + i * seeds[1], bitSize));
            }
            insertionCount++;
        }

        public boolean mightContain(String fingerprint) {
            byte[] value = hashInput(fingerprint);
            long[] seeds = seeds(value);
            for (int i = 0; i < hashCount; i++) {
                if (!bits.get(index(seeds[0] + i * seeds[1], bitSize))) {
                    return false;
                }
            }
            return true;
        }

        public double estimatedFalsePositiveProbability() {
            if (insertionCount == 0) {
                return 0.0;
            }
            double setBitProbability = 1.0 - Math.exp(
                    -(double) hashCount * insertionCount / bitSize);
            return Math.pow(setBitProbability, hashCount);
        }

        private static byte[] hashInput(String fingerprint) {
            if (fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalArgumentException("A non-empty fingerprint is required.");
            }
            return sha256(fingerprint.getBytes(StandardCharsets.UTF_8));
        }

        private static long[] seeds(byte[] digest) {
            ByteBuffer values = ByteBuffer.wrap(digest);
            return new long[]{values.getLong(), values.getLong() | 1L};
        }

        private static int index(long value, int bitSize) {
            return (int) Long.remainderUnsigned(value, bitSize);
        }

        private static byte[] sha256(byte[] input) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(input);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable.", e);
            }
        }
    }
}
