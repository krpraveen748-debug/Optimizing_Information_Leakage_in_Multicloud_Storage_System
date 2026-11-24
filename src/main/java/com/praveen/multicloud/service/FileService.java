package com.praveen.multicloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class FileService {
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final JdbcTemplate jdbc;
    private final ChunkingService chunking;
    private final StorageService storage;
    private final PlacementService placement;
    private final LeakageAwarePlacementService leakagePlacement;
    private final AesGcmEncryptionService encryption;
    private final SimilaritySignatureService similaritySignatures;
    private final ActivityLogService activities;
    private final TransactionTemplate transactions;
    private final long maximumUploadBytes;
    private final Object storageMutationLock = new Object();

    public FileService(
            JdbcTemplate jdbc,
            ChunkingService chunking,
            StorageService storage,
            PlacementService placement,
            LeakageAwarePlacementService leakagePlacement,
            AesGcmEncryptionService encryption,
            SimilaritySignatureService similaritySignatures,
            ActivityLogService activities,
            PlatformTransactionManager transactionManager,
            @Value("${app.upload.max-size-bytes:104857600}") long maximumUploadBytes) {
        if (maximumUploadBytes < 0) {
            throw new IllegalArgumentException("Maximum upload size must not be negative.");
        }
        this.jdbc = jdbc;
        this.chunking = chunking;
        this.storage = storage;
        this.placement = placement;
        this.leakagePlacement = leakagePlacement;
        this.encryption = encryption;
        this.similaritySignatures = similaritySignatures;
        this.activities = activities;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maximumUploadBytes = maximumUploadBytes;
    }

    public Map<String, Object> upload(MultipartFile file, long userId) {
        return upload(file, userId, LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE);
    }

    public Map<String, Object> upload(
            MultipartFile file,
            long userId,
            LeakageAwarePlacementService.Strategy placementStrategy) {
        if (file == null) {
            throw new IllegalArgumentException("A file is required.");
        }
        String originalName = validateFilename(file.getOriginalFilename());
        String contentType = validateContentType(file.getContentType());
        long declaredSize = file.getSize();
        if (declaredSize < 0 || declaredSize > maximumUploadBytes) {
            throw new FileTooLargeException("File exceeds the configured upload size limit.");
        }
        String activeKeyId = encryption.activeKeyId();

        synchronized (storageMutationLock) {
            Set<String> newlyStoredPaths = new LinkedHashSet<>();
            try {
                Map<String, Object> response = transactions.execute(status -> {
                    try {
                        return persistUpload(
                                file, userId, originalName, contentType, declaredSize,
                                activeKeyId, placementStrategy, newlyStoredPaths);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new UploadProcessingException("The file upload could not be completed.", e);
                    }
                });
                if (response == null) {
                    throw new UploadProcessingException("The file upload did not complete.", null);
                }
                return response;
            } catch (RuntimeException e) {
                cleanupFailedUpload(newlyStoredPaths, e);
                throw e;
            }
        }
    }

    public List<Map<String, Object>> listFiles(long userId) {
        return jdbc.queryForList("""
                SELECT f.id,
                       f.original_filename AS original_name,
                       f.file_size AS size_bytes,
                       f.content_type,
                       f.total_chunks,
                       f.upload_status,
                       f.created_at,
                       (SELECT COUNT(*)
                          FROM file_chunks c
                          JOIN chunk_metadata m ON m.chunk_hash = c.chunk_hash
                         WHERE c.file_id = f.id AND m.reference_count > 1) AS deduplicated_chunks
                  FROM files f
                 WHERE f.user_id = ? AND f.upload_status = 'UPLOADED'
                 ORDER BY f.created_at DESC, f.id DESC
                """, userId);
    }

    public Map<String, Object> details(long fileId, long userId) {
        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT id,
                       original_filename AS original_name,
                       content_type,
                       file_size AS size_bytes,
                       file_sha256,
                       placement_strategy,
                       total_chunks,
                       upload_status,
                       created_at
                  FROM files
                 WHERE id = ? AND user_id = ? AND upload_status = 'UPLOADED'
                """, fileId, userId);
        if (files.isEmpty()) {
            throw new NoSuchElementException("File not found.");
        }
        Map<String, Object> file = files.getFirst();
        List<Map<String, Object>> chunks = jdbc.queryForList("""
                SELECT c.chunk_number AS chunk_index,
                       c.chunk_size,
                       m.reference_count > 1 AS deduplicated,
                       p.provider_name AS provider,
                       c.encryption_status
                  FROM file_chunks c
                  JOIN chunk_metadata m ON m.chunk_hash = c.chunk_hash
                  JOIN cloud_providers p ON p.id = c.cloud_provider_id
                 WHERE c.file_id = ?
                 ORDER BY c.chunk_number
                """, fileId);
        file.put("chunks", chunks);
        long deduplicatedCount = chunks.stream()
                .filter(chunk -> Boolean.TRUE.equals(chunk.get("deduplicated")))
                .count();
        Set<String> encryptionStatuses = new LinkedHashSet<>();
        for (Map<String, Object> chunk : chunks) {
            encryptionStatuses.add(String.valueOf(chunk.get("encryption_status")));
        }
        file.put("deduplicatedChunks", deduplicatedCount);
        file.put("encryptionStatus", encryptionStatuses.isEmpty()
                ? "NOT_APPLICABLE"
                : encryptionStatuses.size() == 1
                        ? encryptionStatuses.iterator().next()
                        : "MIXED");
        return file;
    }

    public DownloadedFile download(long fileId, long userId) throws IOException {
        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT file_size, file_sha256, total_chunks, original_filename, upload_status
                  FROM files
                 WHERE id = ? AND user_id = ? AND upload_status = 'UPLOADED'
                """, fileId, userId);
        if (files.isEmpty()) {
            throw new NoSuchElementException("File not found.");
        }
        Map<String, Object> file = files.getFirst();
        List<Map<String, Object>> chunks = jdbc.queryForList("""
                SELECT chunk_number, chunk_hash, chunk_size, storage_path,
                       encryption_status, encryption_iv, encryption_key_id
                  FROM file_chunks
                 WHERE file_id = ?
                 ORDER BY chunk_number
                """, fileId);
        int expectedChunks = ((Number) file.get("total_chunks")).intValue();
        if (chunks.size() != expectedChunks) {
            throw new FileIntegrityException("Stored file chunk metadata is incomplete.");
        }

        ByteArrayOutputStream content = new ByteArrayOutputStream();
        long totalBytes = 0;
        int expectedChunkNumber = 0;
        for (Map<String, Object> chunk : chunks) {
            int chunkNumber = ((Number) chunk.get("chunk_number")).intValue();
            if (chunkNumber != expectedChunkNumber++) {
                throw new FileIntegrityException("Stored file chunk order is invalid.");
            }
            byte[] storedContent = storage.read((String) chunk.get("storage_path"));
            int expectedChunkSize = ((Number) chunk.get("chunk_size")).intValue();
            String expectedChunkHash = (String) chunk.get("chunk_hash");
            byte[] data = switch ((String) chunk.get("encryption_status")) {
                case "ENCRYPTED" -> decryptStoredChunk(
                        storedContent, (String) chunk.get("encryption_iv"),
                        (String) chunk.get("encryption_key_id"), expectedChunkHash, expectedChunkSize);
                case "NOT_ENCRYPTED" -> storedContent;
                default -> throw new FileIntegrityException("Stored file encryption metadata is invalid.");
            };
            if (data.length != expectedChunkSize || !ChunkingService.sha256(data).equals(expectedChunkHash)) {
                throw new FileIntegrityException("Stored file chunk integrity check failed.");
            }
            content.write(data);
            totalBytes += data.length;
        }

        byte[] reconstructed = content.toByteArray();
        long expectedSize = ((Number) file.get("file_size")).longValue();
        if (totalBytes != expectedSize) {
            throw new FileIntegrityException("Reconstructed file size does not match its metadata.");
        }
        String expectedChecksum = (String) file.get("file_sha256");
        if (expectedChecksum != null && !ChunkingService.sha256(reconstructed).equalsIgnoreCase(expectedChecksum)) {
            throw new FileIntegrityException("Reconstructed file checksum verification failed.");
        }
        DownloadedFile downloaded =
                new DownloadedFile(reconstructed, (String) file.get("original_filename"));
        activities.record(userId, "FILE_DOWNLOAD",
                "Downloaded file " + downloaded.filename() + ".",
                downloaded.filename(), "COMPLETED");
        return downloaded;
    }

    private byte[] decryptStoredChunk(
            byte[] ciphertext, String nonce, String keyId, String plaintextHash, int plaintextSize) {
        try {
            return encryption.decrypt(ciphertext, nonce, keyId,
                    AesGcmEncryptionService.chunkAssociatedData(plaintextHash, plaintextSize, keyId));
        } catch (AesGcmEncryptionService.CiphertextIntegrityException e) {
            throw new FileIntegrityException("Stored file chunk integrity check failed.", e);
        }
    }

    public void delete(long fileId, long userId) {
        synchronized (storageMutationLock) {
            DeleteResult deletion = transactions.execute(status -> deleteFileRows(fileId, userId));
            if (deletion == null) {
                throw new IllegalStateException("File deletion did not complete.");
            }
            for (String path : deletion.paths()) {
                try {
                    storage.delete(path);
                } catch (IOException e) {
                    log.error("Could not remove unreferenced chunk content after file deletion.", e);
                    throw new StorageCleanupException("File metadata was deleted, but storage cleanup failed.", e);
                }
            }
            activities.record(userId, "FILE_DELETE",
                    "Deleted file " + deletion.filename() + ".",
                    deletion.filename(), "COMPLETED");
        }
    }

    private Map<String, Object> persistUpload(
            MultipartFile file,
            long userId,
            String originalName,
            String contentType,
            long declaredSize,
            String activeKeyId,
            LeakageAwarePlacementService.Strategy placementStrategy,
            Set<String> newlyStoredPaths) throws Exception {
        LeakageAwarePlacementService.Strategy selectedStrategy = placementStrategy == null
                ? LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE
                : placementStrategy;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String storedName = UUID.randomUUID().toString();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO files (
                        user_id, original_filename, stored_filename, file_size, content_type,
                        total_chunks, upload_status, created_at, file_sha256, placement_strategy
                    ) VALUES (?, ?, ?, ?, ?, 0, 'UPLOADING', CURRENT_TIMESTAMP(6), NULL, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, originalName);
            statement.setString(3, storedName);
            statement.setLong(4, declaredSize);
            statement.setString(5, contentType);
            statement.setString(6, selectedStrategy.name());
            return statement;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("Database did not return a file ID.");
        }
        long fileId = generatedId.longValue();
        int[] deduplicatedChunks = {0};

        List<LeakageAwarePlacementService.ChunkCandidate> placementCandidates;
        List<ChunkingService.Chunk> plannedChunks;
        Map<Integer, String> providerByChunkIndex = new LinkedHashMap<>();
        try (InputStream planInput = file.getInputStream()) {
            plannedChunks = chunking.split(planInput);
            placementCandidates = plannedChunks.stream()
                    .map(chunk -> new LeakageAwarePlacementService.ChunkCandidate(
                            chunk.hash(),
                            chunk.data().length,
                            similaritySignatures.generateSerialized(chunk.data())))
                    .toList();
        }
        LeakageAwarePlacementService.PlacementPlan placementPlan =
                leakagePlacement.createPlan(selectedStrategy, placementCandidates);
        for (ChunkingService.Chunk chunk : plannedChunks) {
            providerByChunkIndex.put(chunk.index(),
                    placementPlan.assignments().get(chunk.hash()));
        }

        ChunkingService.Summary summary;
        try (InputStream input = file.getInputStream()) {
            summary = chunking.process(input, chunk -> {
                ExistingEncryptedChunk reusable = findEncryptedChunk(chunk.hash(), chunk.data().length, activeKeyId);
                StorageProvider provider;
                String path;
                String nonce;
                String keyId;
                if (reusable != null) {
                    provider = new StorageProvider(reusable.providerId(), reusable.providerName());
                    path = reusable.storagePath();
                    nonce = reusable.nonce();
                    keyId = reusable.keyId();
                    byte[] existingPlaintext = decryptStoredChunk(
                            storage.read(path),
                            nonce,
                            keyId,
                            chunk.hash(),
                            chunk.data().length);
                    if (!Arrays.equals(existingPlaintext, chunk.data())) {
                        throw new FileIntegrityException("Reused encrypted chunk does not match its plaintext hash.");
                    }
                    deduplicatedChunks[0]++;
                } else {
                    provider = storageProvider(
                            chunk.hash(), chunk.index(),
                            providerByChunkIndex.get(chunk.index()));
                    AesGcmEncryptionService.EncryptedPayload encrypted = encryption.encrypt(
                            chunk.data(),
                            AesGcmEncryptionService.chunkAssociatedData(
                                    chunk.hash(), chunk.data().length, activeKeyId));
                    String storageId = UUID.randomUUID().toString();
                    StoredChunkResult stored = storeWithPlacementFailover(
                            provider.name(), chunk.hash(), storageId, encrypted.ciphertext());
                    provider = stored.provider();
                    path = stored.path();
                    newlyStoredPaths.add(path);
                    nonce = encrypted.nonceBase64();
                    keyId = encrypted.keyId();
                }

                List<Map<String, Object>> existingMetadata = jdbc.queryForList(
                        "SELECT chunk_size, similarity_signature FROM chunk_metadata WHERE chunk_hash = ? FOR UPDATE",
                        chunk.hash());
                if (existingMetadata.isEmpty()) {
                    jdbc.update("""
                            INSERT INTO chunk_metadata (
                                chunk_hash, chunk_size, similarity_signature, reference_count, created_at
                            ) VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP(6))
                            """, chunk.hash(), chunk.data().length,
                            similaritySignatures.generateSerialized(chunk.data()));
                } else {
                    if (((Number) existingMetadata.getFirst().get("chunk_size")).intValue()
                            != chunk.data().length) {
                        throw new FileIntegrityException("Chunk hash conflicts with existing chunk metadata.");
                    }
                    Object storedSignature = existingMetadata.getFirst().get("similarity_signature");
                    if (storedSignature == null
                            || !similaritySignatures.isCompatible((String) storedSignature)) {
                        jdbc.update("""
                                UPDATE chunk_metadata
                                   SET similarity_signature = ?
                                 WHERE chunk_hash = ?
                                """, similaritySignatures.generateSerialized(chunk.data()), chunk.hash());
                    }
                    jdbc.update(
                            "UPDATE chunk_metadata SET reference_count = reference_count + 1 WHERE chunk_hash = ?",
                            chunk.hash());
                }

                jdbc.update("""
                        INSERT INTO file_chunks (
                            file_id, chunk_number, chunk_hash, chunk_size, cloud_provider_id,
                            storage_path, encryption_status, encryption_iv, encryption_key_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'ENCRYPTED', ?, ?, CURRENT_TIMESTAMP(6))
                        """, fileId, chunk.index(), chunk.hash(), chunk.data().length,
                        provider.id(), path, nonce, keyId);
                jdbc.update("""
                        UPDATE cloud_providers
                           SET total_chunks = total_chunks + 1,
                               total_storage_used = total_storage_used + ?
                         WHERE id = ?
                        """, chunk.data().length, provider.id());
            });
        }

        if (summary.totalBytes() != declaredSize) {
            throw new IOException("Uploaded content size changed while being read.");
        }
        jdbc.update("""
                UPDATE files
                   SET total_chunks = ?, upload_status = 'UPLOADED', file_sha256 = ?
                 WHERE id = ?
                """, summary.chunkCount(), summary.sha256(), fileId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("fileName", originalName);
        response.put("contentType", contentType);
        response.put("sizeBytes", summary.totalBytes());
        response.put("chunkCount", summary.chunkCount());
        response.put("deduplicatedChunks", deduplicatedChunks[0]);
        response.put("sha256", summary.sha256());
        response.put("validationStatus", "PASSED");
        response.put("hashAlgorithm", "SHA-256");
        response.put("encryptionStatus",
                summary.chunkCount() == 0 ? "NOT_APPLICABLE" : "ENCRYPTED");
        response.put("encryptionAlgorithm",
                summary.chunkCount() == 0 ? "NOT_APPLICABLE" : "AES-256-GCM");
        response.put("placementStrategy", selectedStrategy.name());
        response.put("uploadStatus", "UPLOADED");
        response.put("providerDistribution", jdbc.queryForList("""
                SELECT p.provider_name AS provider, COUNT(c.id) AS chunk_count,
                       COALESCE(SUM(c.chunk_size), 0) AS storage_bytes
                  FROM cloud_providers p
                  LEFT JOIN file_chunks c
                    ON c.cloud_provider_id = p.id AND c.file_id = ?
                 WHERE p.provider_name IN ('cloud1', 'cloud2', 'cloud3')
                 GROUP BY p.id, p.provider_name
                 ORDER BY p.provider_name
                """, fileId));
        String chunkCount = String.valueOf(summary.chunkCount());
        String deduplicatedCount = String.valueOf(deduplicatedChunks[0]);
        String encryptionStatus = String.valueOf(response.get("encryptionStatus"));
        String providerAllocation = String.valueOf(response.get("providerDistribution"));
        activities.record(userId, "FILE_UPLOAD",
                 "Uploaded " + originalName + " (" + contentType + ", "
                         + formatBytes(summary.totalBytes())
                         + "); server validation passed; " + chunkCount + " chunks; SHA-256 "
                         + summary.sha256() + "; encryption " + encryptionStatus
                         + "; deduplicated chunks " + deduplicatedCount
                         + "; placement strategy " + selectedStrategy.name() + ".",
                 originalName, "COMPLETED");
        activities.record(userId, "FILE_PROCESSED",
                 "Server processing completed for " + originalName + ".",
                 originalName, "COMPLETED");
        activities.record(userId, "CHUNKING_COMPLETED",
                 "Generated " + chunkCount + " chunks for " + originalName + ".",
                 originalName, "COMPLETED");
        activities.record(userId, "HASHING_COMPLETED",
                 "Calculated SHA-256 " + summary.sha256() + " for " + originalName + ".",
                 originalName, "COMPLETED");
        activities.record(userId, "ENCRYPTION_COMPLETED",
                 encryptionStatus.equals("ENCRYPTED")
                         ? "AES-256-GCM encrypted all stored chunks for " + originalName + "."
                         : "Encryption was not applicable because " + originalName + " has no chunks.",
                 originalName, encryptionStatus.equals("ENCRYPTED") ? "COMPLETED" : "NOT_APPLICABLE");
        activities.record(userId, "DEDUPLICATION_COMPLETED",
                 "Reused " + deduplicatedCount + " duplicate chunks for " + originalName + ".",
                 originalName, "COMPLETED");
        activities.record(userId, "STORAGE_PLACEMENT_COMPLETED",
                 "Placed " + originalName + " using " + selectedStrategy.name()
                         + "; provider allocation: " + providerAllocation + ".",
                 originalName, "COMPLETED");
        return response;
    }

    private ExistingEncryptedChunk findEncryptedChunk(String hash, int chunkSize, String keyId) {
        List<ExistingEncryptedChunk> existing = jdbc.query("""
                SELECT c.storage_path, c.encryption_iv, c.encryption_key_id,
                       c.cloud_provider_id, p.provider_name
                  FROM file_chunks c
                  JOIN cloud_providers p ON p.id = c.cloud_provider_id
                 WHERE c.chunk_hash = ?
                   AND c.chunk_size = ?
                   AND c.encryption_status = 'ENCRYPTED'
                   AND c.encryption_key_id = ?
                 ORDER BY c.id
                 LIMIT 1
                """, (result, row) -> new ExistingEncryptedChunk(
                result.getString("storage_path"),
                result.getString("encryption_iv"),
                result.getString("encryption_key_id"),
                result.getLong("cloud_provider_id"),
                result.getString("provider_name")), hash, chunkSize, keyId);
        return existing.isEmpty() ? null : existing.getFirst();
    }

    private StorageProvider storageProvider(
            String chunkHash, int chunkNumber, String plannedProviderName) {
        List<StorageProvider> existing = jdbc.query("""
                SELECT p.id, p.provider_name
                  FROM file_chunks c
                  JOIN cloud_providers p ON p.id = c.cloud_provider_id
                 WHERE c.chunk_hash = ?
                 ORDER BY c.id
                 LIMIT 1
                """, (result, row) -> new StorageProvider(
                result.getLong("id"), result.getString("provider_name")), chunkHash);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        String providerName = plannedProviderName;
        if (providerName == null || providerName.isBlank()) {
            providerName = placement.roundRobin(chunkNumber);
        }
        List<StorageProvider> selected = jdbc.query("""
                SELECT id, provider_name
                  FROM cloud_providers
                 WHERE provider_name = ? AND status = 'ACTIVE'
                """, (result, row) -> new StorageProvider(
                result.getLong("id"), result.getString("provider_name")), providerName);
        if (selected.isEmpty()) {
            throw new IllegalStateException("No active local storage provider is configured.");
        }
        return selected.getFirst();
    }

    private StoredChunkResult storeWithPlacementFailover(
            String preferredProvider,
            String chunkHash,
            String storageId,
            byte[] ciphertext) throws IOException {
        List<String> candidates = new ArrayList<>();
        if (preferredProvider != null) candidates.add(preferredProvider);
        for (String provider : LeakageAwarePlacementService.PROVIDERS) {
            if (!candidates.contains(provider)) candidates.add(provider);
        }

        IOException firstFailure = null;
        for (String providerName : candidates) {
            List<StorageProvider> providers = jdbc.query("""
                    SELECT id, provider_name
                      FROM cloud_providers
                     WHERE provider_name = ? AND status = 'ACTIVE'
                    """, (row, index) -> new StorageProvider(
                    row.getLong("id"), row.getString("provider_name")),
                    providerName);
            if (providers.isEmpty()) continue;
            try {
                String path = storage.storeEncryptedChunk(
                        providerName, chunkHash, storageId, ciphertext);
                return new StoredChunkResult(providers.getFirst(), path);
            } catch (IOException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        throw new IOException("No active provider could store the encrypted chunk.");
    }

    private DeleteResult deleteFileRows(long fileId, long userId) {
        List<String> filenames = jdbc.query(
                "SELECT original_filename FROM files WHERE id = ? AND user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1),
                fileId, userId);
        if (filenames.isEmpty()) {
            throw new NoSuchElementException("File not found.");
        }
        String originalFilename = filenames.getFirst();

        List<ChunkReference> references = jdbc.query("""
                SELECT chunk_hash, chunk_size, cloud_provider_id, storage_path
                  FROM file_chunks
                 WHERE file_id = ?
                """, (result, row) -> new ChunkReference(
                result.getString("chunk_hash"),
                result.getInt("chunk_size"),
                result.getLong("cloud_provider_id"),
                result.getString("storage_path")), fileId);
        Map<String, Integer> referenceCounts = new LinkedHashMap<>();
        for (ChunkReference reference : references) {
            referenceCounts.merge(reference.hash(), 1, Integer::sum);
        }

        jdbc.update("DELETE FROM leakage_analysis WHERE file_id = ?", fileId);
        jdbc.update("DELETE FROM file_chunks WHERE file_id = ?", fileId);
        for (Map.Entry<String, Integer> entry : referenceCounts.entrySet()) {
            List<Long> counts = jdbc.query(
                    "SELECT reference_count FROM chunk_metadata WHERE chunk_hash = ? FOR UPDATE",
                    (result, row) -> result.getLong(1),
                    entry.getKey());
            if (counts.isEmpty() || counts.getFirst() < entry.getValue()) {
                throw new FileIntegrityException("Chunk reference count is inconsistent.");
            }
            long remaining = counts.getFirst() - entry.getValue();
            jdbc.update("UPDATE chunk_metadata SET reference_count = ? WHERE chunk_hash = ?",
                    remaining, entry.getKey());
            if (remaining == 0) {
                jdbc.update("DELETE FROM chunk_metadata WHERE chunk_hash = ?", entry.getKey());
            }
        }
        for (ChunkReference reference : references) {
            jdbc.update("""
                    UPDATE cloud_providers
                       SET total_chunks = GREATEST(total_chunks - 1, 0),
                           total_storage_used = GREATEST(total_storage_used - ?, 0)
                     WHERE id = ?
                    """, reference.size(), reference.providerId());
        }
        jdbc.update("DELETE FROM files WHERE id = ?", fileId);

        Set<String> candidatePaths = new LinkedHashSet<>();
        for (ChunkReference reference : references) {
            candidatePaths.add(reference.path());
        }
        List<String> unreferencedPaths = new ArrayList<>();
        for (String path : candidatePaths) {
            Long referencesRemaining = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM file_chunks WHERE storage_path = ?", Long.class, path);
            if (referencesRemaining != null && referencesRemaining == 0) {
                unreferencedPaths.add(path);
            }
        }
        return new DeleteResult(unreferencedPaths, originalFilename);
    }

    private void cleanupFailedUpload(Set<String> paths, RuntimeException failure) {
        for (String path : paths) {
            try {
                storage.delete(path);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                log.error("Failed to clean up incomplete upload content.", cleanupFailure);
            }
        }
    }

    private static String validateFilename(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("A file name is required.");
        }
        String normalized = filename.trim();
        if (normalized.isEmpty() || normalized.length() > 255
                || normalized.equals(".") || normalized.equals("..")
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("File name is invalid.");
        }
        return normalized;
    }

    private static String validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String normalized = contentType.trim();
        if (normalized.length() > 150) {
            throw new IllegalArgumentException("File type is invalid.");
        }
        try {
            MediaType parsed = MediaType.parseMediaType(normalized);
            if (parsed.isWildcardType() || parsed.isWildcardSubtype()) {
                throw new IllegalArgumentException("File type must be specific.");
            }
            return parsed.toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("File type is invalid.", e);
        }
    }

    private static String formatBytes(long bytes) {
        return bytes < 1024 ? bytes + " bytes" : String.format("%.1f KiB", bytes / 1024.0);
    }

    private record ChunkReference(String hash, int size, long providerId, String path) {}
    private record DeleteResult(List<String> paths, String filename) {}
    private record StorageProvider(long id, String name) {}
    private record ExistingEncryptedChunk(
            String storagePath, String nonce, String keyId, long providerId, String providerName) {}
    private record StoredChunkResult(StorageProvider provider, String path) {}

    public record DownloadedFile(byte[] content, String filename) {}

    public static class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(String message) {
            super(message);
        }
    }

    public static class FileIntegrityException extends RuntimeException {
        public FileIntegrityException(String message) {
            super(message);
        }

        public FileIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class UploadProcessingException extends RuntimeException {
        public UploadProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class StorageCleanupException extends RuntimeException {
        public StorageCleanupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
