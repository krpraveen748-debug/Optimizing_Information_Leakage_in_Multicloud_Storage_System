package com.praveen.multicloud.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class StorageService {
    private final Map<String, Path> cloudDirectories;

    public StorageService(
            @Value("${app.storage.cloud1:./storage/cloud1}") String cloud1Directory,
            @Value("${app.storage.cloud2:./storage/cloud2}") String cloud2Directory,
            @Value("${app.storage.cloud3:./storage/cloud3}") String cloud3Directory) {
        this.cloudDirectories = Map.of(
                "cloud1", Path.of(cloud1Directory).toAbsolutePath().normalize(),
                "cloud2", Path.of(cloud2Directory).toAbsolutePath().normalize(),
                "cloud3", Path.of(cloud3Directory).toAbsolutePath().normalize());
    }

    @PostConstruct
    void initializeDirectories() throws IOException {
        Set<Path> uniqueDirectories = new HashSet<>(cloudDirectories.values());
        if (uniqueDirectories.size() != cloudDirectories.size()) {
            throw new IllegalStateException("Each cloud provider must use a separate storage directory.");
        }
        for (Path directory : cloudDirectories.values()) {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
                throw new IllegalStateException("Storage directory is not writable: " + directory);
            }
        }
    }

    public String save(String cloud, long fileId, int chunkIndex, byte[] data) throws Exception {
        Path cloudDirectory = cloudDirectories.get(cloud);
        if (cloudDirectory == null) throw new SecurityException("Unknown cloud provider.");

        Path root = cloudDirectory.toRealPath();
        Path dir = cloudDirectory.resolve("file-" + fileId).normalize();
        if (!dir.startsWith(cloudDirectory)) throw new SecurityException("Invalid storage path.");
        createDirectoryInsideRoot(dir, root);
        Path target = dir.resolve("chunk-" + chunkIndex + ".bin").normalize();
        if (!target.startsWith(cloudDirectory)) throw new SecurityException("Invalid storage path.");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            ensureInsideRoot(target, root);
        }
        Files.write(target, data);
        return Paths.get(cloud, "file-" + fileId, "chunk-" + chunkIndex + ".bin").toString();
    }

    public boolean storeUniqueChunk(String cloud, String sha256, byte[] data) throws IOException {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || !ChunkingService.sha256(data).equals(sha256)) {
            throw new IllegalArgumentException("Chunk hash does not match its content.");
        }
        Path target = chunkPath(cloud, sha256);
        Path parent = target.getParent();
        Path root = cloudDirectories.get(cloud).toRealPath();
        createDirectoryInsideRoot(parent.getParent(), root);
        createDirectoryInsideRoot(parent, root);
        ensureInsideRoot(parent, root);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            ensureInsideRoot(target, root);
            verifyExistingChunk(target, data);
            return false;
        }

        Path temporary = Files.createTempFile(parent, ".upload-", ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, target);
                ensureInsideRoot(target, root);
                return true;
            } catch (FileAlreadyExistsException e) {
                ensureInsideRoot(target, root);
                verifyExistingChunk(target, data);
                return false;
            } catch (IOException e) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    ensureInsideRoot(target, root);
                    verifyExistingChunk(target, data);
                    return false;
                }
                throw e;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public String storeEncryptedChunk(String cloud, String sha256, String storageId, byte[] ciphertext)
            throws IOException {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || storageId == null || !storageId.matches("[0-9a-f-]{36}")
                || ciphertext == null || ciphertext.length < 16) {
            throw new IllegalArgumentException("Encrypted chunk metadata is invalid.");
        }
        Path cloudDirectory = cloudDirectories.get(cloud);
        if (cloudDirectory == null) throw new SecurityException("Unknown cloud provider.");
        Path root = cloudDirectory.toRealPath();
        Path encryptedDirectory = cloudDirectory.resolve("encrypted-chunks");
        createDirectoryInsideRoot(encryptedDirectory, root);
        Path prefixDirectory = encryptedDirectory.resolve(sha256.substring(0, 2));
        createDirectoryInsideRoot(prefixDirectory, root);
        Path target = prefixDirectory.resolve(sha256 + "-" + storageId + ".chunk").normalize();
        if (!target.startsWith(cloudDirectory)) throw new SecurityException("Invalid storage path.");

        Path temporary = Files.createTempFile(prefixDirectory, ".encrypted-upload-", ".tmp");
        try {
            Files.write(temporary, ciphertext);
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Paths.get(cloud, "encrypted-chunks", sha256.substring(0, 2),
                sha256 + "-" + storageId + ".chunk").toString();
    }

    public String uniqueChunkPath(String cloud, String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid chunk hash.");
        }
        chunkPath(cloud, sha256);
        return Paths.get(cloud, "chunks", sha256.substring(0, 2), sha256 + ".chunk").toString();
    }

    public byte[] read(String relativePath) throws IOException {
        return Files.readAllBytes(resolveStoragePath(relativePath));
    }

    public boolean delete(String relativePath) throws IOException {
        Path target = resolveStoragePath(relativePath);
        return Files.deleteIfExists(target);
    }

    private Path resolveStoragePath(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Storage path must not be empty.");
        }
        Path relative = Path.of(relativePath).normalize();
        if (relative.isAbsolute() || relative.getNameCount() < 2) {
            throw new SecurityException("Invalid storage path.");
        }
        Path cloudDirectory = cloudDirectories.get(relative.getName(0).toString());
        if (cloudDirectory == null) throw new SecurityException("Unknown cloud provider.");

        Path target = cloudDirectory.resolve(relative.subpath(1, relative.getNameCount())).normalize();
        if (!target.startsWith(cloudDirectory)) throw new SecurityException("Invalid storage path.");
        Path realRoot = cloudDirectory.toRealPath();
        Path parent = target.getParent();
        if (parent != null && Files.exists(parent)) {
            ensureInsideRoot(parent, realRoot);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            ensureInsideRoot(target, realRoot);
        }
        return target;
    }

    private Path chunkPath(String cloud, String sha256) {
        Path cloudDirectory = cloudDirectories.get(cloud);
        if (cloudDirectory == null) throw new SecurityException("Unknown cloud provider.");
        Path target = cloudDirectory.resolve("chunks")
                .resolve(sha256.substring(0, 2))
                .resolve(sha256 + ".chunk")
                .normalize();
        if (!target.startsWith(cloudDirectory)) throw new SecurityException("Invalid storage path.");
        return target;
    }

    private static void verifyExistingChunk(Path target, byte[] expected) throws IOException {
        byte[] existing = Files.readAllBytes(target);
        if (!Arrays.equals(existing, expected)) {
            throw new IOException("Stored chunk content does not match its SHA-256 address.");
        }
    }

    private static void ensureInsideRoot(Path path, Path realRoot) throws IOException {
        if (!path.toRealPath().startsWith(realRoot)) {
            throw new SecurityException("Invalid storage path.");
        }
    }

    private static void createDirectoryInsideRoot(Path directory, Path realRoot) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            ensureInsideRoot(directory, realRoot);
            return;
        }
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException e) {
            ensureInsideRoot(directory, realRoot);
            return;
        }
        ensureInsideRoot(directory, realRoot);
    }
}
