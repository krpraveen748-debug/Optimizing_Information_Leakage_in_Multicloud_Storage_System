package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsChunksFromTheConfiguredProviderDirectory() throws Exception {
        Path cloud1 = tempDir.resolve("one");
        Path cloud2 = tempDir.resolve("two");
        Path cloud3 = tempDir.resolve("three");
        StorageService storage = new StorageService(
                cloud1.toString(), cloud2.toString(), cloud3.toString());
        storage.initializeDirectories();
        byte[] content = {1, 2, 3};

        String relativePath = storage.save("cloud2", 7, 0, content);

        assertEquals(Path.of("cloud2", "file-7", "chunk-0.bin").toString(), relativePath);
        assertArrayEquals(content, storage.read(relativePath));
        assertTrue(Files.exists(cloud2.resolve("file-7/chunk-0.bin")));
        assertFalse(Files.exists(cloud1.resolve("file-7")));
    }

    @Test
    void rejectsUnknownProvidersAndPathsOutsideConfiguredDirectories() throws Exception {
        StorageService storage = new StorageService(
                tempDir.resolve("one").toString(),
                tempDir.resolve("two").toString(),
                tempDir.resolve("three").toString());

        assertThrows(SecurityException.class, () -> storage.save("other", 1, 0, new byte[0]));
        assertThrows(SecurityException.class, () -> storage.read("../../outside/file.bin"));
    }

    @Test
    void refusesToWriteThroughSymlinkedStorageDirectories() throws Exception {
        Path cloud1 = tempDir.resolve("one");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        StorageService storage = new StorageService(
                cloud1.toString(),
                tempDir.resolve("two").toString(),
                tempDir.resolve("three").toString());
        storage.initializeDirectories();
        Files.createSymbolicLink(cloud1.resolve("chunks"), outside);

        byte[] content = {7, 8, 9};
        String hash = ChunkingService.sha256(content);
        assertThrows(SecurityException.class, () -> storage.storeUniqueChunk("cloud1", hash, content));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }
}
