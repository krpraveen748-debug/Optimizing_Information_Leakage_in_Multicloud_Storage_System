package com.praveen.multicloud.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

@Service
public class MinHashService {
    private static final long VALUE_MASK = 0xffff_ffffL;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MIN_PERMUTATIONS = 8;
    private static final int MAX_PERMUTATIONS = 384;
    private static final int MAX_SERIALIZED_LENGTH = 4096;

    private final int permutationCount;
    private final long seed;
    private final long[] multipliers;
    private final long[] offsets;

    public MinHashService(
            @Value("${app.similarity.minhash.permutations:128}") int permutationCount,
            @Value("${app.similarity.minhash.seed:20261119}") long seed) {
        if (permutationCount < MIN_PERMUTATIONS || permutationCount > MAX_PERMUTATIONS) {
            throw new IllegalArgumentException("MinHash permutation count must be between 8 and 384.");
        }
        this.permutationCount = permutationCount;
        this.seed = seed;
        this.multipliers = new long[permutationCount];
        this.offsets = new long[permutationCount];
        initializeHashFamily();
    }

    public Signature signature(Collection<Long> features) {
        Objects.requireNonNull(features, "features");
        long[] values = new long[permutationCount];
        Arrays.fill(values, VALUE_MASK);
        boolean empty = true;
        for (Long feature : features) {
            if (feature == null || feature < 0 || feature > VALUE_MASK) {
                throw new IllegalArgumentException("MinHash features must be unsigned 32-bit values.");
            }
            empty = false;
            long value = feature;
            for (int i = 0; i < permutationCount; i++) {
                long mapped = (multipliers[i] * value + offsets[i]) & VALUE_MASK;
                if (mapped < values[i]) {
                    values[i] = mapped;
                }
            }
        }
        return new Signature(seed, empty, values);
    }

    public double estimateJaccard(Signature first, Signature second) {
        requireCompatible(first, second);
        if (first.empty && second.empty) {
            return 1.0;
        }
        if (first.empty || second.empty) {
            return 0.0;
        }
        int matches = 0;
        for (int i = 0; i < permutationCount; i++) {
            if (first.values[i] == second.values[i]) {
                matches++;
            }
        }
        return (double) matches / permutationCount;
    }

    public Signature union(Collection<Signature> signatures) {
        Objects.requireNonNull(signatures, "signatures");
        long[] values = new long[permutationCount];
        Arrays.fill(values, VALUE_MASK);
        boolean hasFeatures = false;
        for (Signature signature : signatures) {
            requireCompatible(signature);
            if (signature.empty) {
                continue;
            }
            hasFeatures = true;
            for (int i = 0; i < permutationCount; i++) {
                values[i] = Math.min(values[i], signature.values[i]);
            }
        }
        return new Signature(seed, !hasFeatures, values);
    }

    public Signature parse(String serialized) {
        if (serialized == null || serialized.isBlank() || serialized.length() > MAX_SERIALIZED_LENGTH) {
            throw new IllegalArgumentException("Stored MinHash signature is invalid.");
        }
        String[] parts = serialized.split("\\|", 5);
        if (parts.length != 5 || !"mh1".equals(parts[0])) {
            throw new IllegalArgumentException("Stored MinHash signature has an unsupported format.");
        }
        try {
            long storedSeed = Long.parseLong(parts[1]);
            int storedCount = Integer.parseInt(parts[2]);
            boolean empty = switch (parts[3]) {
                case "0" -> false;
                case "1" -> true;
                default -> throw new IllegalArgumentException("Stored MinHash signature is invalid.");
            };
            if (storedSeed != seed || storedCount != permutationCount) {
                throw new IllegalArgumentException("Stored MinHash signature uses different settings.");
            }
            String[] encodedValues = parts[4].split(",", -1);
            if (encodedValues.length != permutationCount) {
                throw new IllegalArgumentException("Stored MinHash signature length is invalid.");
            }
            long[] values = new long[permutationCount];
            for (int i = 0; i < encodedValues.length; i++) {
                values[i] = Long.parseUnsignedLong(encodedValues[i], 16);
                if (values[i] > VALUE_MASK) {
                    throw new IllegalArgumentException("Stored MinHash signature value is invalid.");
                }
            }
            return new Signature(storedSeed, empty, values);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Stored MinHash signature is invalid.", e);
        }
    }

    public int permutationCount() {
        return permutationCount;
    }

    public long seed() {
        return seed;
    }

    private void initializeHashFamily() {
        MessageDigest digest = newSha256();
        for (int i = 0; i < permutationCount; i++) {
            digest.reset();
            byte[] generated = digest.digest(
                    ("multicloud-minhash-v1:" + seed + ":" + i).getBytes(StandardCharsets.UTF_8));
            ByteBuffer values = ByteBuffer.wrap(generated);
            multipliers[i] = Integer.toUnsignedLong(values.getInt()) | 1L;
            offsets[i] = Integer.toUnsignedLong(values.getInt());
        }
    }

    private void requireCompatible(Signature signature) {
        if (signature == null || signature.seed != seed || signature.values.length != permutationCount) {
            throw new IllegalArgumentException("MinHash signatures use incompatible settings.");
        }
    }

    private void requireCompatible(Signature first, Signature second) {
        requireCompatible(first);
        requireCompatible(second);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    public record Signature(long seed, boolean empty, long[] values) {
        public Signature {
            Objects.requireNonNull(values, "values");
            if (values.length < MIN_PERMUTATIONS || values.length > MAX_PERMUTATIONS) {
                throw new IllegalArgumentException("MinHash signature length is invalid.");
            }
            for (long value : values) {
                if (value < 0 || value > VALUE_MASK) {
                    throw new IllegalArgumentException("MinHash signature values must be unsigned 32-bit values.");
                }
                if (empty && value != VALUE_MASK) {
                    throw new IllegalArgumentException("Empty MinHash signatures must contain only sentinel values.");
                }
            }
            values = values.clone();
        }

        @Override
        public long[] values() {
            return values.clone();
        }

        public String serialize() {
            StringBuilder output = new StringBuilder()
                    .append("mh1|").append(seed).append('|').append(values.length).append('|')
                    .append(empty ? '1' : '0').append('|');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    output.append(',');
                }
                for (int shift = 28; shift >= 0; shift -= 4) {
                    output.append(HEX[(int) (values[i] >>> shift) & 0x0f]);
                }
            }
            return output.toString();
        }
    }
}
