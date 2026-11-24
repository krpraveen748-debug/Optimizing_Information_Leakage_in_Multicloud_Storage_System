package com.praveen.multicloud.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class ChunkingService {
    private final int chunkSize;

    public ChunkingService(@Value("${app.chunk.size:65536}") int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be greater than zero.");
        }
        this.chunkSize = chunkSize;
    }

    public List<Chunk> split(InputStream input) throws Exception {
        List<Chunk> chunks = new ArrayList<>();
        process(input, chunks::add);
        return chunks;
    }

    public Summary process(InputStream input, ChunkConsumer consumer) throws Exception {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(consumer, "consumer");
        MessageDigest fileDigest = newSha256();
        byte[] buffer = new byte[chunkSize];
        long totalBytes = 0;
        int chunkNumber = 0;
        int bytesRead;
        while ((bytesRead = readChunk(input, buffer)) > 0) {
            byte[] data = java.util.Arrays.copyOf(buffer, bytesRead);
            fileDigest.update(data);
            consumer.accept(new Chunk(chunkNumber++, data, sha256(data)));
            totalBytes += bytesRead;
        }
        return new Summary(chunkNumber, totalBytes, HexFormat.of().formatHex(fileDigest.digest()));
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(newSha256().digest(data));
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = input.read(buffer, total, buffer.length - total);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    break;
                }
                buffer[total++] = (byte) single;
            } else {
                total += count;
            }
        }
        return total;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(Chunk chunk) throws Exception;
    }

    public record Chunk(int index, byte[] data, String hash) {}
    public record Summary(int chunkCount, long totalBytes, String sha256) {}
}
